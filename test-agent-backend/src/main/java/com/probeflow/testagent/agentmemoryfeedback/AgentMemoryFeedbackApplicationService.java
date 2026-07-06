package com.probeflow.testagent.agentmemoryfeedback;

import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
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

    public AgentMemoryFeedbackApplicationService(
        MemoryCandidateRecordRepository candidates,
        TaskRepository tasks,
        MemoryFeedbackSanitizer sanitizer
    ) {
        this.candidates = candidates;
        this.tasks = tasks;
        this.sanitizer = sanitizer;
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
            request.metadata() == null ? Map.of() : Map.copyOf(request.metadata())
        );
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
        return Map.copyOf(audit);
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
                    records.add(Map.copyOf(copied));
                }
            }
        }
        var handoff = new LinkedHashMap<String, Object>();
        handoff.put("candidateId", record.getCandidateId());
        handoff.put("sourceType", record.getSourceType().name());
        handoff.put("sourceRef", record.getSourceRef());
        handoff.put("status", record.getStatus().name());
        handoff.put("writesLongTermMemory", false);
        handoff.put("createdAt", record.getCreatedAt() == null ? Instant.now().toString() : record.getCreatedAt().toString());
        records.add(Map.copyOf(handoff));
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
