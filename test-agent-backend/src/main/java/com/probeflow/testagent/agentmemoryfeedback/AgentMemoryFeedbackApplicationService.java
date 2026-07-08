package com.probeflow.testagent.agentmemoryfeedback;

import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.failureanalysis.FailureClassification;
import com.probeflow.testagent.failureanalysis.SuiteDependencyFailure;
import com.probeflow.testagent.failureanalysis.SuiteFailureAnalysis;
import com.probeflow.testagent.failureanalysis.SuiteFailureStep;
import com.probeflow.testagent.failureanalysis.SuiteVariableFailure;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.humanintheloop.HumanFeedbackMemoryCandidateStatus;
import com.probeflow.testagent.humanintheloop.HumanInTheLoopApplicationService;
import com.probeflow.testagent.memory.MemoryCandidateRequest;
import com.probeflow.testagent.memory.MemoryRefineryResult;
import com.probeflow.testagent.memory.MemoryRefineryService;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.orchestration.StepOutcome;
import com.probeflow.testagent.policyvalidator.PolicyValidationReasonCode;
import com.probeflow.testagent.policyvalidator.PolicyValidationResult;
import com.probeflow.testagent.task.PlanStepStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    @Transactional
    public AgentMemoryFeedbackResult refineFailureAnalysisCandidate(
        AgentMemoryCandidateSourceType candidateSourceType,
        MemoryCandidateRequest candidate
    ) {
        if (candidate == null) {
            return AgentMemoryFeedbackResult.rejected(
                "invalid-failure-analysis-candidate",
                List.of("Memory candidate is required"),
                Map.of("writesLongTermMemory", false)
            );
        }
        if (candidateSourceType != AgentMemoryCandidateSourceType.EXECUTION_RECORD
            && candidateSourceType != AgentMemoryCandidateSourceType.FAILURE_ANALYSIS) {
            return AgentMemoryFeedbackResult.rejected(
                "unsupported-failure-analysis-source",
                List.of("Failure analysis candidates must come from EXECUTION_RECORD or FAILURE_ANALYSIS"),
                Map.of("sourceType", String.valueOf(candidateSourceType), "writesLongTermMemory", false)
            );
        }

        var sanitizedCandidate = sanitizeMemoryCandidate(candidate);
        var tags = enrichedFailureAnalysisTags(sanitizedCandidate, candidateSourceType);
        var metadata = metadataWithPhase7FailureAnalysis(sanitizedCandidate.metadata(), candidateSourceType);
        var intake = new AgentMemoryCandidateIntakeRequest(
            candidateSourceType,
            sanitizedCandidate.sourceRef(),
            sanitizedCandidate.taskId(),
            sanitizedCandidate.summary(),
            sanitizedCandidate.content(),
            sanitizedCandidate.rawEvidence(),
            tags,
            sanitizedCandidate.confidence(),
            metadata
        );
        return submitAndRefine(intake, new MemoryCandidateRequest(
            sanitizedCandidate.summary(),
            sanitizedCandidate.content(),
            sanitizedCandidate.sourceType(),
            sanitizedCandidate.sourceRef(),
            sanitizedCandidate.taskId(),
            tags,
            sanitizedCandidate.confidence(),
            sanitizedCandidate.rawEvidence(),
            metadata
        ));
    }

    @Transactional
    public AgentMemoryFeedbackResult refineSuiteFailureAnalysisCandidate(SuiteFailureMemoryFeedbackRequest request) {
        var blockers = validateSuiteFailureRequest(request);
        if (!blockers.isEmpty()) {
            return AgentMemoryFeedbackResult.rejected(
                "invalid-suite-failure-analysis-candidate",
                blockers,
                Map.of("blockers", blockers, "writesLongTermMemory", false, "refineryInvoked", false)
            );
        }

        var analysis = request.suiteFailureAnalysis();
        if (analysis.classification() == FailureClassification.NONE
            || analysis.classification() == FailureClassification.SKIPPED) {
            return AgentMemoryFeedbackResult.rejected(
                "suite-failure-not-learnable",
                List.of("Suite failure analysis has no learnable failure classification"),
                Map.of(
                    "taskId", request.taskId(),
                    "executionId", request.executionId(),
                    "classification", analysis.classification().name(),
                    "writesLongTermMemory", false,
                    "refineryInvoked", false
                )
            );
        }

        var candidate = suiteFailureCandidate(request);
        var intake = new AgentMemoryCandidateIntakeRequest(
            AgentMemoryCandidateSourceType.FAILURE_ANALYSIS,
            candidate.sourceRef(),
            candidate.taskId(),
            candidate.summary(),
            candidate.content(),
            candidate.rawEvidence(),
            candidate.tags(),
            candidate.confidence(),
            candidate.metadata()
        );
        if (request.requiresHumanReview() || highRiskSuiteRecovery(request) || candidate.confidence() < 0.55f) {
            return submitCandidate(intake);
        }
        return submitAndRefine(intake, candidate);
    }

    @Transactional
    public AgentMemoryFeedbackResult refineStepOutcomeCandidate(String taskId, String stepId, StepOutcome outcome) {
        var normalizedTaskId = normalizeNullable(taskId);
        var normalizedStepId = normalizeNullable(stepId);
        if (outcome == null || normalizedTaskId == null || normalizedStepId == null) {
            var audit = new LinkedHashMap<String, Object>();
            audit.put("taskId", normalizedTaskId);
            audit.put("stepId", normalizedStepId);
            audit.put("writesLongTermMemory", false);
            return AgentMemoryFeedbackResult.rejected(
                "invalid-step-outcome-candidate",
                List.of("taskId, stepId and StepOutcome are required"),
                compact(audit)
            );
        }
        if (outcome.stepStatus() == PlanStepStatus.SUCCESS) {
            return AgentMemoryFeedbackResult.rejected(
                "step-outcome-not-learnable",
                List.of("Successful StepOutcome is not a reusable failure memory candidate"),
                Map.of(
                    "taskId", normalizedTaskId,
                    "stepId", normalizedStepId,
                    "stepStatus", outcome.stepStatus().name(),
                    "writesLongTermMemory", false
                )
            );
        }

        var classification = stepOutcomeClassification(outcome);
        var riskLevel = stepOutcomeRiskLevel(outcome, classification);
        var retryable = stepOutcomeRetryable(outcome, classification);
        var confidence = stepOutcomeConfidence(outcome, riskLevel);
        var sourceRef = "step-outcome:" + normalizedStepId;
        var metadata = stepOutcomeMetadata(normalizedStepId, outcome, classification, riskLevel, retryable);
        var tags = stepOutcomeTags(classification, riskLevel, retryable);
        var summary = classification + " in step " + normalizedStepId;
        var content = "Step " + normalizedStepId
            + " finished with " + outcome.stepStatus()
            + ". Summary: " + outcome.summary()
            + ". Blockers: " + outcome.blockerDetails()
            + ". Retryable: " + retryable + ".";
        var evidence = "StepOutcome resultRefs=" + outcome.resultRefs()
            + " resultRef=" + outcome.resultRef()
            + " blockers=" + outcome.blockerDetails()
            + " stopOrchestration=" + outcome.stopOrchestration();

        return submitAndRefine(
            new AgentMemoryCandidateIntakeRequest(
                AgentMemoryCandidateSourceType.STEP_OUTCOME,
                sourceRef,
                normalizedTaskId,
                summary,
                content,
                evidence,
                tags,
                confidence,
                metadata
            ),
            new MemoryCandidateRequest(
                summary,
                content,
                MemorySourceType.EXECUTION_RESULT,
                sourceRef,
                normalizedTaskId,
                tags,
                confidence,
                evidence,
                metadata
            )
        );
    }

    @Transactional
    public AgentMemoryFeedbackResult refinePolicyLearningNote(
        String taskId,
        PolicyValidationResult validation,
        PlanDecision decision,
        Map<String, Object> contextMetadata
    ) {
        var normalizedTaskId = normalizeNullable(taskId);
        if (normalizedTaskId == null || validation == null) {
            var audit = new LinkedHashMap<String, Object>();
            audit.put("taskId", normalizedTaskId);
            audit.put("writesLongTermMemory", false);
            return AgentMemoryFeedbackResult.rejected(
                "invalid-policy-learning-note",
                List.of("taskId and PolicyValidationResult are required"),
                compact(audit)
            );
        }
        if (validation.allowed()) {
            return AgentMemoryFeedbackResult.rejected(
                "policy-validation-not-learnable",
                List.of("Allowed policy validations do not create learning notes"),
                Map.of("taskId", normalizedTaskId, "reasonCode", validation.reasonCode().name(), "writesLongTermMemory", false)
            );
        }

        var context = contextMetadata == null ? Map.<String, Object>of() : contextMetadata;
        var action = policyAction(validation, decision);
        var toolName = policyToolName(validation, decision);
        var riskLevel = policyRiskLevel(decision);
        var saferAlternative = recommendedSaferAlternative(validation.reasonCode(), action, toolName);
        var confidence = policyLearningConfidence(validation, decision);
        var sourceRef = "policy-validation:" + policyDecisionRef(validation, decision);
        var summary = "Policy rejected " + action + " for " + toolName + " because " + validation.reasonCode();
        var content = "Policy rejected planner action " + action
            + " using tool " + toolName
            + ". Reason: " + validation.message()
            + ". Blockers: " + validation.blockers()
            + ". Recommended safer alternative: " + saferAlternative + ".";
        var metadata = policyLearningMetadata(validation, decision, context, riskLevel, toolName, action, saferAlternative);
        var tags = policyLearningTags(validation, decision, context, riskLevel, toolName, action);
        var evidence = "PolicyValidationResult=" + validation.auditSummary()
            + " PlanDecision=" + (decision == null ? Map.of() : decision.auditSummary())
            + " context=" + context;

        return submitAndRefine(
            new AgentMemoryCandidateIntakeRequest(
                AgentMemoryCandidateSourceType.POLICY_VALIDATION_RESULT,
                sourceRef,
                normalizedTaskId,
                summary,
                content,
                evidence,
                tags,
                confidence,
                metadata
            ),
            new MemoryCandidateRequest(
                summary,
                content,
                MemorySourceType.OBSERVATION,
                sourceRef,
                normalizedTaskId,
                tags,
                confidence,
                evidence,
                metadata
            )
        );
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
            var refineryResult = memoryRefinery.refine(sanitizeMemoryCandidate(refineryRequest));
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

    private List<String> enrichedFailureAnalysisTags(
        MemoryCandidateRequest candidate,
        AgentMemoryCandidateSourceType candidateSourceType
    ) {
        var tags = new LinkedHashSet<>(normalizeTags(candidate.tags()));
        tags.add("failure-analysis");
        tags.add(candidateSourceType == AgentMemoryCandidateSourceType.EXECUTION_RECORD
            ? "execution-record"
            : "task-failure-pattern");
        var riskLevel = metadataString(candidate.metadata().get("riskLevel"));
        if ("HIGH".equals(riskLevel) || "CRITICAL".equals(riskLevel)) {
            tags.add("high-risk");
        }
        if (Boolean.TRUE.equals(candidate.metadata().get("retryable"))) {
            tags.add("retryable");
        }
        return List.copyOf(tags);
    }

    private Map<String, Object> metadataWithPhase7FailureAnalysis(
        Map<String, Object> candidateMetadata,
        AgentMemoryCandidateSourceType candidateSourceType
    ) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.putAll(candidateMetadata == null ? Map.of() : candidateMetadata);
        metadata.put("phase", "V2_PHASE_7");
        metadata.put("handoffType", "FAILURE_ANALYSIS_MEMORY_FEEDBACK_REFINERY");
        metadata.put("candidateSourceType", candidateSourceType.name());
        metadata.put("writesLongTermMemory", true);
        return compact(metadata);
    }

    private MemoryCandidateRequest suiteFailureCandidate(SuiteFailureMemoryFeedbackRequest request) {
        var analysis = request.suiteFailureAnalysis();
        var classification = analysis.classification();
        var sourceRef = suiteFailureSourceRef(request);
        var tags = suiteFailureTags(analysis);
        var metadata = suiteFailureMetadata(request, sourceRef);
        return new MemoryCandidateRequest(
            suiteFailureSummary(request, analysis),
            suiteFailureContent(request, analysis),
            MemorySourceType.EXECUTION_RESULT,
            sourceRef,
            request.taskId().trim(),
            tags,
            request.confidence(),
            suiteFailureEvidence(request, analysis),
            metadata
        );
    }

    private List<String> validateSuiteFailureRequest(SuiteFailureMemoryFeedbackRequest request) {
        var blockers = new ArrayList<String>();
        if (request == null) {
            return List.of("request is required");
        }
        if (!StringUtils.hasText(request.taskId())) {
            blockers.add("taskId is required");
        }
        if (!StringUtils.hasText(request.executionId())) {
            blockers.add("executionId is required");
        }
        if (request.suiteFailureAnalysis() == null) {
            blockers.add("suiteFailureAnalysis is required");
        } else {
            if (request.suiteFailureAnalysis().classification() == null) {
                blockers.add("failureClassification is required");
            }
            if (request.suiteFailureAnalysis().rootCauseStep() == null) {
                blockers.add("root cause step evidence is required");
            }
            if (request.suiteFailureAnalysis().variableFailure() == null
                && request.suiteFailureAnalysis().dependencyFailure() == null
                && request.evidence().isEmpty()) {
                blockers.add("suite failure evidence is required");
            }
        }
        if (!StringUtils.hasText(request.nextSuggestion())) {
            blockers.add("nextSuggestion is required");
        }
        if (request.confidence() == null || request.confidence() < 0.0f || request.confidence() > 1.0f) {
            blockers.add("confidence must be between 0.0 and 1.0");
        }
        return List.copyOf(blockers);
    }

    private String suiteFailureSourceRef(SuiteFailureMemoryFeedbackRequest request) {
        var analysis = request.suiteFailureAnalysis();
        return "suite-failure-analysis:"
            + request.executionId().trim()
            + ":"
            + analysis.classification().name()
            + ":"
            + suiteFailureFingerprint(analysis);
    }

    private String suiteFailureFingerprint(SuiteFailureAnalysis analysis) {
        var parts = new ArrayList<String>();
        parts.add(stepId(analysis.rootCauseStep()));
        parts.add(analysis.classification().name());
        if (analysis.variableFailure() != null) {
            var variable = analysis.variableFailure();
            parts.add(metadataString(variable.stepId()));
            parts.add(metadataString(firstText(variable.targetKey(), variable.path(), variable.expression())));
            parts.add(metadataString(variable.extractRuleId()));
            parts.add(metadataString(variable.sourcePath()));
        }
        if (analysis.dependencyFailure() != null) {
            var dependency = analysis.dependencyFailure();
            parts.add(metadataString(dependency.producerStepId()));
            parts.add(metadataString(dependency.consumerStepId()));
            parts.add(metadataString(firstText(dependency.variableName(), dependency.path(), dependency.expression())));
        }
        analysis.affectedDownstreamSteps().stream().map(this::stepId).forEach(parts::add);
        return Integer.toHexString(String.join("|", parts).hashCode());
    }

    private String suiteFailureSummary(SuiteFailureMemoryFeedbackRequest request, SuiteFailureAnalysis analysis) {
        return "V3 SUITE " + analysis.classification().name()
            + " at " + stepId(analysis.rootCauseStep())
            + " affected " + analysis.affectedDownstreamSteps().stream().map(this::stepId).toList();
    }

    private String suiteFailureContent(SuiteFailureMemoryFeedbackRequest request, SuiteFailureAnalysis analysis) {
        var classification = analysis.classification();
        var variable = analysis.variableFailure();
        var dependency = analysis.dependencyFailure();
        var content = new StringBuilder();
        content.append("Scenario: V3 SUITE execution ")
            .append(nullToBlank(request.executionId()))
            .append(" for case ")
            .append(nullToBlank(request.caseId()))
            .append(". Failure type: ")
            .append(classification.name())
            .append(". Root cause step: ")
            .append(stepId(analysis.rootCauseStep()))
            .append(". Affected downstream steps: ")
            .append(analysis.affectedDownstreamSteps().stream().map(this::stepId).toList())
            .append(". ");
        if (variable != null) {
            content.append("Variable evidence: failed variable ")
                .append(firstText(variable.targetKey(), variable.path(), variable.expression()))
                .append(" at ")
                .append(nullToBlank(variable.location()))
                .append(", extractRule ")
                .append(nullToBlank(variable.extractRuleId()))
                .append(" from ")
                .append(nullToBlank(variable.sourceType()))
                .append(" ")
                .append(nullToBlank(variable.sourcePath()))
                .append(". ");
        }
        if (dependency != null) {
            content.append("Dependency evidence: producer ")
                .append(nullToBlank(dependency.producerStepId()))
                .append(" order ")
                .append(dependency.producerOrder())
                .append(", consumer ")
                .append(nullToBlank(dependency.consumerStepId()))
                .append(" order ")
                .append(dependency.consumerOrder())
                .append(", variable ")
                .append(firstText(dependency.variableName(), dependency.path(), dependency.expression()))
                .append(". ");
        }
        if (classification == FailureClassification.BUSINESS_PRECONDITION_FAILURE) {
            content.append("Business precondition evidence: state ")
                .append(metadataString(request.metadata().get("businessState")))
                .append(", missing precondition step ")
                .append(metadataString(request.metadata().get("missingPreconditionStep")))
                .append(", test data requirement ")
                .append(metadataString(request.metadata().get("testDataRequirement")))
                .append(". ");
        }
        if (classification == FailureClassification.DOWNSTREAM_API_FAILURE) {
            content.append("Downstream API evidence: prerequisite variables were already produced/consumed; learn this as service or endpoint behavior, not as a variable, precondition, or step-order fix. ");
        }
        content.append("Root cause: ")
            .append(rootCauseText(analysis))
            .append(". Recommendation: ")
            .append(request.nextSuggestion())
            .append(". Later SUITE generation should reuse this only when the same variable, dependency, or business precondition evidence is present.");
        return content.toString();
    }

    private String suiteFailureEvidence(SuiteFailureMemoryFeedbackRequest request, SuiteFailureAnalysis analysis) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("suiteId", request.suiteId());
        evidence.put("caseId", request.caseId());
        evidence.put("executionId", request.executionId());
        evidence.put("classification", analysis.classification().name());
        evidence.put("rootStep", suiteStepMap(analysis.rootCauseStep()));
        evidence.put("firstFailingStep", suiteStepMap(analysis.firstFailingStep()));
        evidence.put("directlyFailedStep", suiteStepMap(analysis.directlyFailedStep()));
        evidence.put("affectedDownstreamSteps", analysis.affectedDownstreamSteps().stream().map(this::suiteStepMap).toList());
        evidence.put("dependentSkippedSteps", analysis.dependentSkippedSteps().stream().map(this::suiteStepMap).toList());
        evidence.put("variableFailure", suiteVariableMap(analysis.variableFailure()));
        evidence.put("dependencyFailure", suiteDependencyMap(analysis.dependencyFailure()));
        evidence.put("diagnosticEvidence", request.evidence());
        evidence.put("nextSuggestion", request.nextSuggestion());
        evidence.put("impactSummary", analysis.impactSummary());
        return evidence.toString();
    }

    private Map<String, Object> suiteFailureMetadata(SuiteFailureMemoryFeedbackRequest request, String sourceRef) {
        var analysis = request.suiteFailureAnalysis();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.putAll(request.metadata());
        metadata.put("phase", "V3_PHASE_6");
        metadata.put("handoffType", "SUITE_FAILURE_ANALYSIS_MEMORY_FEEDBACK");
        metadata.put("suiteId", request.suiteId());
        metadata.put("caseId", request.caseId());
        metadata.put("executionId", request.executionId());
        metadata.put("sourceRef", sourceRef);
        metadata.put("failureClassification", analysis.classification().name());
        metadata.put("recoveryActionType", request.recoveryActionType());
        metadata.put("requiresHumanReview", request.requiresHumanReview());
        metadata.put("confidence", request.confidence());
        metadata.put("rootStepId", stepId(analysis.rootCauseStep()));
        metadata.put("affectedDownstreamStepIds", analysis.affectedDownstreamSteps().stream().map(this::stepId).toList());
        metadata.put("variableFailure", suiteVariableMap(analysis.variableFailure()));
        metadata.put("dependencyFailure", suiteDependencyMap(analysis.dependencyFailure()));
        metadata.put("nextSuggestion", request.nextSuggestion());
        metadata.put("writesLongTermMemory", !(request.requiresHumanReview() || highRiskSuiteRecovery(request) || request.confidence() < 0.55f));
        return compact(metadata);
    }

    private List<String> suiteFailureTags(SuiteFailureAnalysis analysis) {
        var tags = new LinkedHashSet<String>();
        tags.add("v3");
        tags.add("suite");
        tags.add("failure-analysis");
        tags.add("memory-feedback");
        tags.add(analysis.classification().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        switch (analysis.classification()) {
            case VARIABLE_EXTRACTION_FAILURE, INVALID_EXTRACT_RULE, UNSUPPORTED_EXTRACT_SOURCE -> tags.add("variable-extraction");
            case VARIABLE_RESOLUTION_FAILURE, VARIABLE_WRITEBACK_FAILURE, VARIABLE_OVERWRITE_RISK -> tags.add("variable-resolution");
            case DEPENDENCY_ORDER_FAILURE -> tags.add("dependency-order");
            case BUSINESS_PRECONDITION_FAILURE, SUITE_PREREQUISITE_FAILURE, PREREQUISITE_STEP_FAILURE -> tags.add("business-precondition");
            case DOWNSTREAM_API_FAILURE -> tags.add("downstream-api");
            default -> tags.add("suite-failure");
        }
        return List.copyOf(tags);
    }

    private boolean highRiskSuiteRecovery(SuiteFailureMemoryFeedbackRequest request) {
        var action = metadataString(request.recoveryActionType()).toUpperCase(Locale.ROOT);
        return "WAIT_FOR_HUMAN".equals(action)
            || "PROVIDE_INPUT".equals(action)
            || Boolean.TRUE.equals(request.metadata().get("highRiskRecovery"));
    }

    private String rootCauseText(SuiteFailureAnalysis analysis) {
        if (analysis.variableFailure() != null && StringUtils.hasText(analysis.variableFailure().failureReason())) {
            return analysis.variableFailure().failureReason();
        }
        if (analysis.dependencyFailure() != null && StringUtils.hasText(analysis.dependencyFailure().failureReason())) {
            return analysis.dependencyFailure().failureReason();
        }
        return StringUtils.hasText(analysis.impactSummary()) ? analysis.impactSummary() : analysis.classification().name();
    }

    private Map<String, Object> suiteStepMap(SuiteFailureStep step) {
        if (step == null) {
            return Map.of();
        }
        var map = new LinkedHashMap<String, Object>();
        map.put("stepId", step.stepId());
        map.put("stepName", step.stepName());
        map.put("order", step.order());
        map.put("apiSpecId", step.apiSpecId());
        map.put("status", step.status());
        map.put("statusCode", step.statusCode());
        map.put("message", step.message());
        map.put("skipReason", step.skipReason());
        return compact(map);
    }

    private Map<String, Object> suiteVariableMap(SuiteVariableFailure variable) {
        if (variable == null) {
            return Map.of();
        }
        var map = new LinkedHashMap<String, Object>();
        map.put("classification", variable.classification() == null ? null : variable.classification().name());
        map.put("stepId", variable.stepId());
        map.put("expression", variable.expression());
        map.put("location", variable.location());
        map.put("scope", variable.scope());
        map.put("path", variable.path());
        map.put("extractRuleId", variable.extractRuleId());
        map.put("sourceType", variable.sourceType());
        map.put("sourcePath", variable.sourcePath());
        map.put("targetScope", variable.targetScope());
        map.put("targetKey", variable.targetKey());
        map.put("failureReason", variable.failureReason());
        map.put("oldValueSummary", variable.oldValueSummary());
        map.put("newValueSummary", variable.newValueSummary());
        return compact(map);
    }

    private Map<String, Object> suiteDependencyMap(SuiteDependencyFailure dependency) {
        if (dependency == null) {
            return Map.of();
        }
        var map = new LinkedHashMap<String, Object>();
        map.put("producerStepId", dependency.producerStepId());
        map.put("producerOrder", dependency.producerOrder());
        map.put("consumerStepId", dependency.consumerStepId());
        map.put("consumerOrder", dependency.consumerOrder());
        map.put("expression", dependency.expression());
        map.put("scope", dependency.scope());
        map.put("path", dependency.path());
        map.put("variableName", dependency.variableName());
        map.put("failureReason", dependency.failureReason());
        return compact(map);
    }

    private String stepId(SuiteFailureStep step) {
        return step == null ? "" : metadataString(step.stepId());
    }

    private String firstText(Object... values) {
        for (var value : values) {
            if (value != null && StringUtils.hasText(value.toString())) {
                return value.toString().trim();
            }
        }
        return "";
    }

    private String nullToBlank(Object value) {
        return Objects.toString(value, "");
    }

    private Map<String, Object> stepOutcomeMetadata(
        String stepId,
        StepOutcome outcome,
        String classification,
        String riskLevel,
        boolean retryable
    ) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("phase", "V2_PHASE_7");
        metadata.put("handoffType", "STEP_OUTCOME_MEMORY_FEEDBACK_REFINERY");
        metadata.put("stepId", stepId);
        metadata.put("classification", classification);
        metadata.put("riskLevel", riskLevel);
        metadata.put("retryable", retryable);
        metadata.put("stepStatus", outcome.stepStatus().name());
        if (outcome.taskStatus() != null) {
            metadata.put("taskStatus", outcome.taskStatus().name());
        }
        metadata.put("resultRefs", outcome.resultRefs());
        metadata.put("blockers", outcome.blockerDetails());
        metadata.put("stopOrchestration", outcome.stopOrchestration());
        metadata.put("errorCode", classification);
        metadata.put("writesLongTermMemory", true);
        return compact(metadata);
    }

    private List<String> stepOutcomeTags(String classification, String riskLevel, boolean retryable) {
        var tags = new LinkedHashSet<String>();
        tags.add("phase7");
        tags.add("failure-analysis");
        tags.add("step-outcome");
        tags.add(classification.toLowerCase(Locale.ROOT));
        if ("HIGH".equals(riskLevel) || "CRITICAL".equals(riskLevel)) {
            tags.add("high-risk");
        }
        if (retryable) {
            tags.add("retryable");
        }
        return List.copyOf(tags);
    }

    private String stepOutcomeClassification(StepOutcome outcome) {
        var combined = stepOutcomeCombinedText(outcome);
        if (containsAny(combined, List.of("auth", "401", "403", "permission", "credential"))) {
            return "AUTH_ISSUE";
        }
        if (containsAny(combined, List.of("timeout", "timed out", "connection", "network"))) {
            return "TIMEOUT";
        }
        if (containsAny(combined, List.of("policy", "high risk", "unsafe", "security"))) {
            return "HIGH_RISK_STEP_FAILURE";
        }
        if (outcome.stepStatus() == PlanStepStatus.SKIPPED) {
            return "BLOCKED_STEP";
        }
        return "STEP_FAILURE";
    }

    private String stepOutcomeRiskLevel(StepOutcome outcome, String classification) {
        if (outcome.taskStatus() == TaskStatus.FAILED || outcome.stopOrchestration()
            || "HIGH_RISK_STEP_FAILURE".equals(classification)) {
            return "HIGH";
        }
        if (!outcome.blockerDetails().isEmpty()) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private boolean stepOutcomeRetryable(StepOutcome outcome, String classification) {
        if ("TIMEOUT".equals(classification)) {
            return true;
        }
        return containsAny(stepOutcomeCombinedText(outcome), List.of("retry", "temporary", "transient", "environment"));
    }

    private float stepOutcomeConfidence(StepOutcome outcome, String riskLevel) {
        if ("HIGH".equals(riskLevel)) {
            return 0.86f;
        }
        if ("MEDIUM".equals(riskLevel) && outcome.stepStatus() == PlanStepStatus.FAILED) {
            return 0.64f;
        }
        return 0.42f;
    }

    private String stepOutcomeCombinedText(StepOutcome outcome) {
        return (outcome.summary() + " " + outcome.blockerDetails() + " " + outcome.resultRefs() + " " + outcome.resultRef())
            .toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String input, List<String> tokens) {
        for (var token : tokens) {
            if (input.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> policyLearningMetadata(
        PolicyValidationResult validation,
        PlanDecision decision,
        Map<String, Object> context,
        String riskLevel,
        String toolName,
        String action,
        String saferAlternative
    ) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("phase", "V2_PHASE_7");
        metadata.put("handoffType", "POLICY_LEARNING_NOTE_REFINERY");
        metadata.put("decisionId", policyDecisionRef(validation, decision));
        metadata.put("validationStatus", validation.status().name());
        metadata.put("policyReason", validation.reasonCode().name());
        metadata.put("errorCode", validation.reasonCode().name());
        metadata.put("blockedAction", action);
        metadata.put("plannerAction", action);
        metadata.put("toolName", toolName);
        metadata.put("proposedToolName", toolName);
        metadata.put("riskLevel", riskLevel);
        metadata.put("plannerConfidence", decision == null ? null : decision.confidence());
        metadata.put("plannerReasoning", decision == null ? null : decision.reasoning());
        metadata.put("sourceLlmCallId", validation.sourceLlmCallId());
        metadata.put("sourceTrigger", metadataString(context.get("sourceTrigger")));
        metadata.put("recommendedSaferAlternative", saferAlternative);
        metadata.put("blockers", validation.blockers());
        metadata.put("stageProfile", metadataString(context.get("stageProfile")));
        metadata.put("apiPath", metadataString(context.get("apiPath")));
        metadata.put("module", metadataString(context.get("module")));
        metadata.put("systemName", metadataString(context.get("systemName")));
        metadata.put("context", context);
        metadata.put("writesLongTermMemory", true);
        if (decision != null && decision.proposedPlanStep() != null) {
            metadata.put("proposedStepType", decision.proposedPlanStep().stepType());
            metadata.put("proposedStepTitle", decision.proposedPlanStep().title());
            metadata.put("proposedStepDescription", decision.proposedPlanStep().description());
            metadata.put("proposedStepToolName", decision.proposedPlanStep().proposedToolName());
        }
        return compact(metadata);
    }

    private List<String> policyLearningTags(
        PolicyValidationResult validation,
        PlanDecision decision,
        Map<String, Object> context,
        String riskLevel,
        String toolName,
        String action
    ) {
        var tags = new LinkedHashSet<String>();
        tags.add("phase7");
        tags.add("policy-learning");
        tags.add("policy-guardrail");
        tags.add(validation.reasonCode().name().toLowerCase(Locale.ROOT));
        tags.add(validation.status().name().toLowerCase(Locale.ROOT));
        tags.add(action.toLowerCase(Locale.ROOT));
        tags.add(riskLevel.toLowerCase(Locale.ROOT));
        if (StringUtils.hasText(toolName) && !"unknown-tool".equals(toolName)) {
            tags.add(toolName.toLowerCase(Locale.ROOT));
        }
        if (decision != null && decision.proposedPlanStep() != null) {
            tags.add(decision.proposedPlanStep().stepType().toLowerCase(Locale.ROOT));
        }
        var contextTags = context.get("tags");
        if (contextTags instanceof Iterable<?> values) {
            for (var value : values) {
                if (value != null && StringUtils.hasText(value.toString())) {
                    tags.add(value.toString().trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(tags);
    }

    private String policyDecisionRef(PolicyValidationResult validation, PlanDecision decision) {
        var decisionId = metadataString(validation.decisionId());
        if (StringUtils.hasText(decisionId)) {
            return decisionId + ":" + validation.reasonCode().name();
        }
        if (decision != null && StringUtils.hasText(decision.decisionId())) {
            return decision.decisionId() + ":" + validation.reasonCode().name();
        }
        return validation.reasonCode().name() + ":" + policyAction(validation, decision) + ":" + policyToolName(validation, decision);
    }

    private String policyAction(PolicyValidationResult validation, PlanDecision decision) {
        if (decision != null && decision.action() != null) {
            return decision.action().name();
        }
        return validation.plannerAction() == null ? "UNKNOWN_ACTION" : validation.plannerAction().name();
    }

    private String policyToolName(PolicyValidationResult validation, PlanDecision decision) {
        if (decision != null && StringUtils.hasText(decision.proposedToolName())) {
            return decision.proposedToolName();
        }
        if (decision != null && decision.proposedPlanStep() != null && StringUtils.hasText(decision.proposedPlanStep().proposedToolName())) {
            return decision.proposedPlanStep().proposedToolName();
        }
        return StringUtils.hasText(validation.proposedToolName()) ? validation.proposedToolName() : "unknown-tool";
    }

    private String policyRiskLevel(PlanDecision decision) {
        return decision == null || decision.riskLevel() == null ? "UNKNOWN" : decision.riskLevel().name();
    }

    private float policyLearningConfidence(PolicyValidationResult validation, PlanDecision decision) {
        if (highValuePolicyLearningNote(validation, decision)) {
            return 0.88f;
        }
        if (validation.requiresHumanConfirmation()) {
            return 0.76f;
        }
        return 0.42f;
    }

    private boolean highValuePolicyLearningNote(PolicyValidationResult validation, PlanDecision decision) {
        var riskLevel = decision == null ? ToolRiskLevel.LOW : decision.riskLevel();
        if (riskLevel == ToolRiskLevel.HIGH || riskLevel == ToolRiskLevel.CRITICAL) {
            return true;
        }
        return switch (validation.reasonCode()) {
            case TOOL_NOT_WHITELISTED,
                TOOL_BLOCKED_BY_CONTRACT,
                TOOL_NOT_ALLOWED_IN_TASK_PHASE,
                HIGH_RISK_REQUIRES_CONFIRMATION,
                HUMAN_CONFIRMATION_REQUIRED,
                V1_BOUNDARY_BLOCKED,
                UNKNOWN_TOOL -> true;
            default -> false;
        };
    }

    private String recommendedSaferAlternative(
        PolicyValidationReasonCode reasonCode,
        String action,
        String toolName
    ) {
        return switch (reasonCode) {
            case TOOL_NOT_WHITELISTED, UNKNOWN_TOOL ->
                "Use a tool that is visible and whitelisted by AgentPolicy before proposing " + action + ".";
            case TOOL_BLOCKED_BY_CONTRACT, TOOL_NOT_ALLOWED_IN_TASK_PHASE ->
                "Choose a lower-risk tool allowed in the current task phase instead of " + toolName + ".";
            case HIGH_RISK_REQUIRES_CONFIRMATION, HUMAN_CONFIRMATION_REQUIRED ->
                "Pause for the configured human approval workflow before changing the plan.";
            case V1_BOUNDARY_BLOCKED ->
                "Stay inside the backend Agent Core boundary and avoid external workflow integrations.";
            case MISSING_REQUIRED_INPUT, MISSING_HUMAN_INPUT, HUMAN_INPUT_REQUIRED ->
                "Ask for the missing input through the controlled human-in-the-loop path.";
            case MISSING_CONTEXT_BUNDLE, MISSING_API_SPEC, MISSING_TEST_CASE, MISSING_EXECUTION_RESULT, MISSING_FAILURE_SIGNAL ->
                "Collect the missing prerequisite context before proposing the planner action.";
            default ->
                "Prefer the least-privileged planner action and re-check PolicyValidator before applying changes.";
        };
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
