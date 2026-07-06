package com.probeflow.testagent.agentmemoryfeedback;

import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.humanintheloop.HumanFeedbackMemoryCandidateStatus;
import com.probeflow.testagent.humanintheloop.HumanInTheLoopApplicationService;
import com.probeflow.testagent.memory.MemoryCandidateRequest;
import com.probeflow.testagent.memory.MemoryRefineryResult;
import com.probeflow.testagent.memory.MemoryRefineryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AgentMemoryFeedbackApplicationService {

    private static final int SUMMARY_LIMIT = 512;
    private static final int CONTENT_LIMIT = 4000;

    private final MemoryCandidateRecordRepository candidates;
    private final TaskRepository tasks;
    private final MemoryFeedbackSanitizer sanitizer;
    private final HumanInTheLoopApplicationService humanInTheLoop;
    private final MemoryRefineryService memoryRefinery;

    public AgentMemoryFeedbackApplicationService(
        MemoryCandidateRecordRepository candidates,
        TaskRepository tasks,
        MemoryFeedbackSanitizer sanitizer,
        HumanInTheLoopApplicationService humanInTheLoop,
        MemoryRefineryService memoryRefinery
    ) {
        this.candidates = candidates;
        this.tasks = tasks;
        this.sanitizer = sanitizer;
        this.humanInTheLoop = humanInTheLoop;
        this.memoryRefinery = memoryRefinery;
    }

    @Transactional
    public AgentMemoryFeedbackResult submitCandidate(AgentMemoryCandidateIntakeRequest request) {
        var normalized = normalize(request);
        var validationBlockers = validate(normalized);
        if (!validationBlockers.isEmpty()) {
            return AgentMemoryFeedbackResult.rejected(
                "invalid-candidate",
                validationBlockers,
                Map.of("blockers", validationBlockers, "writesLongTermMemory", false)
            );
        }

        var task = tasks.findById(normalized.taskId());
        if (task.isEmpty()) {
            return AgentMemoryFeedbackResult.rejected(
                "task-not-found",
                List.of("Task not found: " + normalized.taskId()),
                Map.of("taskId", normalized.taskId(), "writesLongTermMemory", false)
            );
        }
        if (task.get().getStatus() == TaskStatus.COMPLETED || task.get().getStatus() == TaskStatus.CANCELLED) {
            return AgentMemoryFeedbackResult.rejected(
                "terminal-task",
                List.of("Terminal task cannot accept memory feedback candidates"),
                Map.of(
                    "taskId", normalized.taskId(),
                    "taskStatus", task.get().getStatus().name(),
                    "writesLongTermMemory", false
                )
            );
        }

        var existing = candidates.findBySourceTypeAndSourceRefAndTaskId(
            normalized.sourceType(),
            normalized.sourceRef(),
            normalized.taskId()
        );
        if (existing.isPresent()) {
            return AgentMemoryFeedbackResult.duplicate(existing.get());
        }

        var record = new MemoryCandidateRecord();
        record.setCandidateId(UUID.randomUUID().toString());
        record.setSourceType(normalized.sourceType());
        record.setSourceRef(normalized.sourceRef());
        record.setTaskId(normalized.taskId());
        record.setStatus(MemoryCandidateProcessingStatus.PENDING);
        record.setSummary(limit(sanitizer.sanitizeText(normalized.summary()), SUMMARY_LIMIT));
        record.setContent(limit(sanitizer.sanitizeText(normalized.content()), CONTENT_LIMIT));
        record.setSanitizedEvidence(limit(sanitizer.sanitizeText(normalized.rawEvidence()), CONTENT_LIMIT));
        record.setTags(normalizeTags(normalized.tags()));
        record.setConfidence(normalized.confidence());
        record.setMetadata(sanitizer.sanitizeMap(normalized.metadata()));
        record.setIdempotencyKey(idempotencyKey(normalized));
        record.setRefineryResultSummary(Map.of("refineryInvoked", false));
        record.setAuditSummary(auditSummary(record, false));

        var saved = candidates.save(record);
        appendTaskMetadata(task.get(), saved);
        return AgentMemoryFeedbackResult.pending(saved);
    }

    @Transactional
    public AgentMemoryFeedbackResult refineHumanDecisionCandidate(String decisionId) {
        var handoff = humanInTheLoop.generateMemoryCandidateForDecision(decisionId);
        if (handoff.status() != HumanFeedbackMemoryCandidateStatus.GENERATED) {
            return AgentMemoryFeedbackResult.rejected(
                "human-feedback-candidate-unavailable",
                handoff.blockers(),
                handoff.auditSummary()
            );
        }
        var sanitizedCandidate = sanitizeMemoryCandidate(boostHumanConfidence(handoff.candidate()));
        var intake = new AgentMemoryCandidateIntakeRequest(
            AgentMemoryCandidateSourceType.HUMAN_DECISION_RECORD,
            sanitizedCandidate.sourceRef(),
            sanitizedCandidate.taskId(),
            sanitizedCandidate.summary(),
            sanitizedCandidate.content(),
            sanitizedCandidate.rawEvidence(),
            enrichedHumanTags(sanitizedCandidate),
            sanitizedCandidate.confidence(),
            metadataWithPhase7Handoff(sanitizedCandidate.metadata(), handoff.auditSummary())
        );
        return submitAndRefine(intake, new MemoryCandidateRequest(
            sanitizedCandidate.summary(),
            sanitizedCandidate.content(),
            sanitizedCandidate.sourceType(),
            sanitizedCandidate.sourceRef(),
            sanitizedCandidate.taskId(),
            intake.tags(),
            sanitizedCandidate.confidence(),
            sanitizedCandidate.rawEvidence(),
            intake.metadata()
        ));
    }

    private AgentMemoryFeedbackResult submitAndRefine(
        AgentMemoryCandidateIntakeRequest intake,
        MemoryCandidateRequest refineryRequest
    ) {
        var intakeResult = submitCandidate(intake);
        if (intakeResult.status() == MemoryCandidateProcessingStatus.DUPLICATE
            || intakeResult.status() == MemoryCandidateProcessingStatus.REJECTED) {
            return intakeResult;
        }

        var record = candidates.findById(intakeResult.candidateId()).orElseThrow();
        try {
            var refineryResult = memoryRefinery.refine(refineryRequest);
            applyRefineryResult(record, refineryResult);
        } catch (RuntimeException exception) {
            record.setStatus(MemoryCandidateProcessingStatus.FAILED);
            record.setRejectionReason("refinery-failed");
            var errorSummary = new LinkedHashMap<String, Object>();
            errorSummary.put("refineryInvoked", true);
            errorSummary.put("error", sanitizer.sanitizeText(exception.getMessage()));
            record.setRefineryResultSummary(compact(errorSummary));
        }
        record.setAuditSummary(processedAuditSummary(record));
        var saved = candidates.save(record);
        tasks.findById(saved.getTaskId()).ifPresent(task -> appendTaskMetadata(task, saved));
        return AgentMemoryFeedbackResult.fromRecord(saved);
    }

    private void applyRefineryResult(MemoryCandidateRecord record, MemoryRefineryResult refineryResult) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("refineryInvoked", true);
        summary.put("accepted", refineryResult.accepted());
        summary.put("created", refineryResult.created());
        summary.put("merged", refineryResult.duplicateSuppressed());
        summary.put("rejectionReason", refineryResult.rejectionReason());
        if (refineryResult.memory() != null) {
            summary.put("memoryId", refineryResult.memory().memoryId());
            summary.put("scopeType", refineryResult.memory().scopeType().name());
            summary.put("sourceType", refineryResult.memory().sourceType().name());
        }
        record.setRefineryResultSummary(compact(summary));
        record.setRejectionReason(refineryResult.rejectionReason());
        if (!refineryResult.accepted()) {
            record.setStatus(MemoryCandidateProcessingStatus.REJECTED);
            return;
        }
        record.setMemoryId(refineryResult.memory() == null ? null : refineryResult.memory().memoryId());
        record.setStatus(refineryResult.created()
            ? MemoryCandidateProcessingStatus.ACCEPTED
            : MemoryCandidateProcessingStatus.MERGED);
    }

    private AgentMemoryCandidateIntakeRequest normalize(AgentMemoryCandidateIntakeRequest request) {
        if (request == null) {
            return new AgentMemoryCandidateIntakeRequest(null, null, null, null, null, null, List.of(), null, Map.of());
        }
        return new AgentMemoryCandidateIntakeRequest(
            request.sourceType(),
            normalizeNullable(request.sourceRef()),
            normalizeNullable(request.taskId()),
            normalizeNullable(request.summary()),
            normalizeNullable(request.content()),
            normalizeNullable(request.rawEvidence()),
            request.tags() == null ? List.of() : List.copyOf(request.tags()),
            request.confidence(),
            request.metadata() == null ? Map.of() : new LinkedHashMap<>(request.metadata())
        );
    }

    private MemoryCandidateRequest sanitizeMemoryCandidate(MemoryCandidateRequest candidate) {
        return new MemoryCandidateRequest(
            sanitizer.sanitizeText(candidate.summary()),
            sanitizer.sanitizeText(candidate.content()),
            candidate.sourceType(),
            normalizeNullable(candidate.sourceRef()),
            normalizeNullable(candidate.taskId()),
            normalizeTags(candidate.tags()),
            candidate.confidence(),
            sanitizer.sanitizeText(candidate.rawEvidence()),
            sanitizer.sanitizeMap(candidate.metadata())
        );
    }

    private MemoryCandidateRequest boostHumanConfidence(MemoryCandidateRequest candidate) {
        var decisionType = metadataString(candidate.metadata().get("decisionType"));
        var requestType = metadataString(candidate.metadata().get("requestType"));
        var boosted = Math.max(candidate.confidence() == null ? 0.0f : candidate.confidence(), confidenceForHumanFeedback(requestType, decisionType));
        return new MemoryCandidateRequest(
            candidate.summary(),
            candidate.content(),
            candidate.sourceType(),
            candidate.sourceRef(),
            candidate.taskId(),
            candidate.tags(),
            Math.min(0.95f, boosted),
            candidate.rawEvidence(),
            candidate.metadata()
        );
    }

    private float confidenceForHumanFeedback(String requestType, String decisionType) {
        if ("PROMOTE_DRAFT".equals(decisionType)
            || "REQUEST_CHANGES".equals(decisionType)
            || "PROVIDE_INPUT".equals(decisionType)
            || "RESOLVE_BLOCKER".equals(decisionType)) {
            return 0.86f;
        }
        if ("HIGH_RISK_APPROVAL".equals(requestType) || "REJECT".equals(decisionType)) {
            return 0.84f;
        }
        return 0.80f;
    }

    private List<String> enrichedHumanTags(MemoryCandidateRequest candidate) {
        var tags = new LinkedHashSet<>(normalizeTags(candidate.tags()));
        var requestType = metadataString(candidate.metadata().get("requestType"));
        var decisionType = metadataString(candidate.metadata().get("decisionType"));
        if ("DRAFT_REVIEW".equals(requestType)) {
            tags.add("testing-pattern");
        }
        if ("REQUEST_CHANGES".equals(decisionType)) {
            tags.add("generation-preference");
        }
        if ("BLOCKER_RESOLUTION".equals(requestType) || "MISSING_INPUT".equals(requestType)) {
            tags.add("project-knowledge");
            tags.add("blocker-resolution");
        }
        if ("PLANNER_CLARIFICATION".equals(requestType)) {
            tags.add("planner-clarification");
            tags.add("project-knowledge");
        }
        if ("HIGH_RISK_APPROVAL".equals(requestType)) {
            tags.add("policy-learning");
            tags.add("high-risk");
        }
        return List.copyOf(tags);
    }

    private Map<String, Object> metadataWithPhase7Handoff(
        Map<String, Object> candidateMetadata,
        Map<String, Object> phase6Audit
    ) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.putAll(candidateMetadata == null ? Map.of() : candidateMetadata);
        metadata.put("originPhase", metadata.getOrDefault("phase", "V2_PHASE_6"));
        metadata.put("phase", "V2_PHASE_7");
        metadata.put("handoffType", "AGENT_MEMORY_FEEDBACK_REFINERY");
        metadata.put("writesLongTermMemory", true);
        metadata.put("phase6HandoffAudit", sanitizer.sanitizeMap(phase6Audit));
        return compact(metadata);
    }

    private List<String> validate(AgentMemoryCandidateIntakeRequest request) {
        var blockers = new ArrayList<String>();
        if (request.sourceType() == null) {
            blockers.add("sourceType is required");
        }
        if (!StringUtils.hasText(request.sourceRef())) {
            blockers.add("sourceRef is required");
        }
        if (!StringUtils.hasText(request.taskId())) {
            blockers.add("taskId is required");
        }
        if (!StringUtils.hasText(request.summary())
            && !StringUtils.hasText(request.content())
            && !StringUtils.hasText(request.rawEvidence())) {
            blockers.add("summary, content or rawEvidence is required");
        }
        if (request.confidence() == null || request.confidence() < 0.0f || request.confidence() > 1.0f) {
            blockers.add("confidence must be between 0.0 and 1.0");
        }
        return List.copyOf(blockers);
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        var normalized = new LinkedHashSet<String>();
        for (var tag : tags) {
            if (StringUtils.hasText(tag)) {
                normalized.add(tag.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(normalized);
    }

    private Map<String, Object> auditSummary(MemoryCandidateRecord record, boolean idempotent) {
        var audit = new LinkedHashMap<String, Object>();
        audit.put("candidateId", record.getCandidateId());
        audit.put("sourceType", record.getSourceType().name());
        audit.put("sourceRef", record.getSourceRef());
        audit.put("taskId", record.getTaskId());
        audit.put("status", record.getStatus().name());
        audit.put("summary", record.getSummary());
        audit.put("tags", record.getTags());
        audit.put("confidence", record.getConfidence());
        audit.put("evidenceStoredAs", "sanitized_evidence");
        audit.put("rawEvidenceIncluded", false);
        audit.put("writesLongTermMemory", false);
        audit.put("refineryInvoked", false);
        audit.put("idempotent", idempotent);
        audit.put("createdAt", Instant.now().toString());
        return compact(audit);
    }

    private Map<String, Object> processedAuditSummary(MemoryCandidateRecord record) {
        var audit = new LinkedHashMap<>(record.getAuditSummary());
        audit.put("status", record.getStatus().name());
        audit.put("memoryId", record.getMemoryId());
        audit.put("rejectionReason", record.getRejectionReason());
        audit.put("refineryInvoked", true);
        audit.put("writesLongTermMemory", record.getMemoryId() != null);
        audit.put("refineryResultSummary", record.getRefineryResultSummary());
        audit.put("updatedAt", Instant.now().toString());
        return compact(audit);
    }

    private void appendTaskMetadata(Task task, MemoryCandidateRecord record) {
        var metadata = task.getMetadata() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<>(task.getMetadata());
        var records = new ArrayList<Map<String, Object>>();
        var existing = metadata.get("agentMemoryFeedbackCandidates");
        if (existing instanceof List<?> values) {
            for (var value : values) {
                if (value instanceof Map<?, ?> map) {
                    var copied = new LinkedHashMap<String, Object>();
                    map.forEach((key, item) -> copied.put(String.valueOf(key), item));
                    if (!record.getCandidateId().equals(metadataString(copied.get("candidateId")))) {
                        records.add(Map.copyOf(copied));
                    }
                }
            }
        }
        var handoff = new LinkedHashMap<String, Object>();
        handoff.put("candidateId", record.getCandidateId());
        handoff.put("sourceType", record.getSourceType().name());
        handoff.put("sourceRef", record.getSourceRef());
        handoff.put("status", record.getStatus().name());
        handoff.put("memoryId", record.getMemoryId());
        handoff.put("writesLongTermMemory", record.getMemoryId() != null);
        handoff.put("refineryInvoked", Boolean.TRUE.equals(record.getRefineryResultSummary().get("refineryInvoked")));
        handoff.put("rejectionReason", record.getRejectionReason());
        handoff.put("createdAt", record.getCreatedAt() == null ? Instant.now().toString() : record.getCreatedAt().toString());
        records.add(compact(handoff));
        metadata.put("agentMemoryFeedbackCandidates", List.copyOf(records));
        task.setMetadata(Map.copyOf(metadata));
        tasks.save(task);
    }

    private String idempotencyKey(AgentMemoryCandidateIntakeRequest request) {
        return request.sourceType().name() + "|" + request.sourceRef() + "|" + request.taskId();
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String metadataString(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private Map<String, Object> compact(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        var compacted = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                compacted.put(key, value);
            }
        });
        return Map.copyOf(compacted);
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        var normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 3).trim() + "...";
    }
}
