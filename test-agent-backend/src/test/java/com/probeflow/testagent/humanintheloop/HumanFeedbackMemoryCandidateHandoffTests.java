package com.probeflow.testagent.humanintheloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.policyvalidator.PolicyValidationReasonCode;
import com.probeflow.testagent.replanning.ReplanningTrigger;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import com.probeflow.testagent.testcasedraft.TestCaseDraft;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HumanFeedbackMemoryCandidateHandoffTests {

    @Autowired
    private HumanInTheLoopApplicationService humanInTheLoop;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TestCaseDraftRepository drafts;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private EntityManager entityManager;

    @Test
    void draftReviewPromoteDiscardAndRequestChangesGenerateAuditableMemoryCandidates() {
        var promote = applyDraftDecision(
            "task-memory-draft-promote",
            "draft-memory-promote",
            HumanDecisionType.PROMOTE_DRAFT,
            "Promote because it covers the happy path.",
            Map.of("reviewDecision", "promote", "draftIds", List.of("draft-memory-promote"))
        );
        var discard = applyDraftDecision(
            "task-memory-draft-discard",
            "draft-memory-discard",
            HumanDecisionType.DISCARD_DRAFT,
            "Discard duplicate edge-case coverage.",
            Map.of("reviewDecision", "discard", "draftIds", List.of("draft-memory-discard"))
        );
        var changes = applyDraftDecision(
            "task-memory-draft-changes",
            "draft-memory-changes",
            HumanDecisionType.REQUEST_CHANGES,
            "Needs a retry assertion.",
            Map.of(
                "reviewDecision", "request_changes",
                "draftIds", List.of("draft-memory-changes"),
                "changeRequest", "Add timeout and retry assertions before promotion."
            )
        );

        var promoteCandidate = humanInTheLoop.generateMemoryCandidateForDecision(promote.getDecisionId());
        var discardCandidate = humanInTheLoop.generateMemoryCandidateForDecision(discard.getDecisionId());
        var changesCandidate = humanInTheLoop.generateMemoryCandidateForDecision(changes.getDecisionId());

        entityManager.flush();
        entityManager.clear();

        assertDraftCandidate(promoteCandidate, HumanDecisionType.PROMOTE_DRAFT, promote.getDecisionId());
        assertDraftCandidate(discardCandidate, HumanDecisionType.DISCARD_DRAFT, discard.getDecisionId());
        assertDraftCandidate(changesCandidate, HumanDecisionType.REQUEST_CHANGES, changes.getDecisionId());
        assertThat(longTermMemories.findAll()).isEmpty();
    }

    @Test
    void blockerHighRiskRejectionAndPlannerClarificationCandidatesMaskSensitivePayloads() {
        var blocker = applyBlockerResolution();
        var highRisk = applyHighRiskRejection();
        var clarification = applyPlannerClarification();

        var blockerCandidate = humanInTheLoop.generateMemoryCandidateForDecision(blocker.getDecisionId());
        var highRiskCandidate = humanInTheLoop.generateMemoryCandidateForDecision(highRisk.getDecisionId());
        var clarificationCandidate = humanInTheLoop.generateMemoryCandidateForDecision(clarification.getDecisionId());

        entityManager.flush();
        entityManager.clear();

        assertCandidate(blockerCandidate, HumanRequestType.MISSING_INPUT, HumanDecisionType.PROVIDE_INPUT, blocker.getDecisionId());
        assertCandidate(highRiskCandidate, HumanRequestType.HIGH_RISK_APPROVAL, HumanDecisionType.REJECT, highRisk.getDecisionId());
        assertCandidate(clarificationCandidate, HumanRequestType.PLANNER_CLARIFICATION, HumanDecisionType.PROVIDE_INPUT, clarification.getDecisionId());
        assertSensitiveTextMasked(blockerCandidate, "secret-blocker-token");
        assertSensitiveTextMasked(highRiskCandidate, "secret-high-risk-token");
        assertSensitiveTextMasked(clarificationCandidate, "secret-clarification-token");
        assertThat(metadataMap(blockerCandidate.candidate().metadata().get("sanitizedSummary")))
            .containsEntry("token", HumanInTheLoopApplicationService.MASKED_VALUE);
        assertThat(metadataMap(highRiskCandidate.candidate().metadata().get("sanitizedSummary")))
            .containsEntry("apiToken", HumanInTheLoopApplicationService.MASKED_VALUE);
        assertThat(metadataMap(clarificationCandidate.candidate().metadata().get("sanitizedSummary")))
            .containsEntry("token", HumanInTheLoopApplicationService.MASKED_VALUE);
        assertThat(longTermMemories.findAll()).isEmpty();
    }

    @Test
    void memoryCandidateAuditIsDecisionScopedAndIdempotent() {
        var blocker = applyBlockerResolution();

        var first = humanInTheLoop.generateMemoryCandidateForDecision(blocker.getDecisionId());
        var second = humanInTheLoop.generateMemoryCandidateForDecision(blocker.getDecisionId());

        entityManager.flush();
        entityManager.clear();

        assertThat(first.status()).isEqualTo(HumanFeedbackMemoryCandidateStatus.GENERATED);
        assertThat(second.status()).isEqualTo(HumanFeedbackMemoryCandidateStatus.GENERATED);
        assertThat(second.auditSummary()).containsEntry("idempotent", true);
        var savedTask = tasks.findById(blocker.getTaskId()).orElseThrow();
        assertThat(metadataRecords(savedTask.getMetadata().get("humanFeedbackMemoryCandidates")))
            .singleElement()
            .satisfies(record -> {
                assertThat(record).containsEntry("decisionId", blocker.getDecisionId());
                assertThat(record).containsEntry("requestId", blocker.getRequestId());
                assertThat(record).containsEntry("sourceRef", "human-decision:" + blocker.getDecisionId());
                assertThat(record).containsEntry("writesLongTermMemory", false);
            });
        assertThat(longTermMemories.findAll()).isEmpty();
    }

    private HumanDecisionRecord applyDraftDecision(
        String taskId,
        String draftId,
        HumanDecisionType decisionType,
        String reason,
        Map<String, Object> payload
    ) {
        var task = saveTask(taskId, TaskStatus.WAITING_FOR_REVIEW, PromotionMode.MANUAL, Map.of());
        drafts.save(draft(task.getTaskId(), draftId));
        var request = humanInTheLoop.createDraftReviewRequest(new HumanDraftReviewRequest(
            task.getTaskId(),
            "step-memory-draft",
            "Manual review is required before execution."
        )).request();
        var result = humanInTheLoop.applyDraftReviewDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            decisionType,
            "reviewer-memory",
            reason,
            payload
        ));
        assertThat(result.status()).isEqualTo(HumanDraftReviewDecisionStatus.APPLIED);
        return result.decision();
    }

    private HumanDecisionRecord applyBlockerResolution() {
        var task = saveTask(
            "task-memory-blocker",
            TaskStatus.WAITING_FOR_REVIEW,
            PromotionMode.AUTO,
            Map.of("lastReplanning", Map.of("resumeTaskStatus", TaskStatus.EXECUTING.name()))
        );
        var request = humanInTheLoop.createRequest(new HumanReviewRequestCreateRequest(
            task.getTaskId(),
            "step-memory-blocker",
            HumanRequestType.MISSING_INPUT,
            "Missing execution input for target environment.",
            List.of(
                Map.of("name", "baseUrl", "type", "string", "required", true),
                Map.of("name", "environment", "type", "string", "required", true),
                Map.of("name", "token", "type", "string", "required", false)
            ),
            ToolRiskLevel.MEDIUM,
            ReplanningTrigger.EXECUTION_READINESS_MISSING.name(),
            "decision-memory-blocker",
            PolicyValidationReasonCode.HUMAN_INPUT_REQUIRED.name(),
            Map.of("blockers", List.of("Missing baseUrl", "Missing environment")),
            null
        )).request();
        var result = humanInTheLoop.applyBlockerResolutionDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.PROVIDE_INPUT,
            "release-owner",
            "Use staging for this run.",
            Map.of(
                "baseUrl", "https://staging.example.test",
                "environment", "staging",
                "token", "secret-blocker-token"
            )
        ));
        assertThat(result.status()).isEqualTo(HumanInputResolutionStatus.APPLIED);
        return result.decision();
    }

    private HumanDecisionRecord applyHighRiskRejection() {
        var task = saveTask("task-memory-high-risk", TaskStatus.WAITING_FOR_REVIEW, PromotionMode.AUTO, Map.of());
        var request = humanInTheLoop.createRequest(new HumanReviewRequestCreateRequest(
            task.getTaskId(),
            "step-memory-high-risk",
            HumanRequestType.HIGH_RISK_APPROVAL,
            "Planner decision is high risk and requires human confirmation.",
            List.of(
                Map.of("name", "approved", "type", "boolean", "required", true),
                Map.of("name", "apiToken", "type", "string", "required", false)
            ),
            ToolRiskLevel.HIGH,
            ReplanningTrigger.FAILURE_ANALYSIS_HIGH_RISK.name(),
            "decision-memory-high-risk",
            PolicyValidationReasonCode.HIGH_RISK_REQUIRES_CONFIRMATION.name(),
            Map.of("blockers", List.of("HIGH_RISK_DECISION")),
            null
        )).request();
        var result = humanInTheLoop.applyHighRiskDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.REJECT,
            "security-owner",
            "Production-like data may be touched; require a safer plan.",
            Map.of("approved", false, "apiToken", "secret-high-risk-token")
        ));
        assertThat(result.status()).isEqualTo(HumanHighRiskDecisionStatus.DENIED);
        return result.decision();
    }

    private HumanDecisionRecord applyPlannerClarification() {
        var task = saveTask("task-memory-clarification", TaskStatus.WAITING_FOR_REVIEW, PromotionMode.AUTO, Map.of());
        var request = humanInTheLoop.createRequest(new HumanReviewRequestCreateRequest(
            task.getTaskId(),
            "step-memory-clarification",
            HumanRequestType.PLANNER_CLARIFICATION,
            "Planner needs a target environment before recovery.",
            List.of(
                Map.of("name", "targetEnvironment", "type", "string", "required", true),
                Map.of("name", "token", "type", "string", "required", false)
            ),
            ToolRiskLevel.HIGH,
            ReplanningTrigger.PLAN_STEP_FAILED.name(),
            "decision-memory-clarification",
            PolicyValidationReasonCode.HUMAN_INPUT_REQUIRED.name(),
            Map.of(
                "question", "Which configured environment should the next suggested step use?",
                "options", List.of("staging", "qa")
            ),
            null
        )).request();
        var result = humanInTheLoop.applyPlannerClarificationDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.PROVIDE_INPUT,
            "api-owner",
            "Use isolated staging.",
            Map.of("targetEnvironment", "staging", "token", "secret-clarification-token")
        ));
        assertThat(result.status()).isEqualTo(HumanPlannerClarificationStatus.APPLIED);
        return result.decision();
    }

    private void assertDraftCandidate(
        HumanFeedbackMemoryCandidateResult result,
        HumanDecisionType decisionType,
        String decisionId
    ) {
        assertCandidate(result, HumanRequestType.DRAFT_REVIEW, decisionType, decisionId);
        assertThat(result.candidate().tags()).contains("draft-review");
        assertThat(result.candidate().summary()).contains("DRAFT_REVIEW").contains(decisionType.name());
    }

    private void assertCandidate(
        HumanFeedbackMemoryCandidateResult result,
        HumanRequestType requestType,
        HumanDecisionType decisionType,
        String decisionId
    ) {
        assertThat(result.status()).isEqualTo(HumanFeedbackMemoryCandidateStatus.GENERATED);
        assertThat(result.candidate().sourceType()).isEqualTo(MemorySourceType.USER_FEEDBACK);
        assertThat(result.candidate().sourceRef()).isEqualTo("human-decision:" + decisionId);
        assertThat(result.candidate().tags())
            .contains("human-feedback", requestType.name().toLowerCase().replace('_', '-'), decisionType.name().toLowerCase().replace('_', '-'));
        assertThat(result.candidate().metadata())
            .containsEntry("requestType", requestType.name())
            .containsEntry("decisionType", decisionType.name())
            .containsEntry("decisionId", decisionId)
            .containsEntry("writesLongTermMemory", false);
        assertThat(result.candidate().content())
            .contains("actor=")
            .contains("reason=")
            .contains("payloadSummary=");
    }

    private void assertSensitiveTextMasked(HumanFeedbackMemoryCandidateResult result, String secret) {
        assertThat(result.candidate().content()).doesNotContain(secret);
        assertThat(result.candidate().rawEvidence()).doesNotContain(secret);
        assertThat(result.candidate().content()).contains(HumanInTheLoopApplicationService.MASKED_VALUE);
        assertThat(result.candidate().rawEvidence()).contains(HumanInTheLoopApplicationService.MASKED_VALUE);
    }

    private Task saveTask(String taskId, TaskStatus status, PromotionMode promotionMode, Map<String, Object> metadata) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Memory candidate " + taskId);
        task.setStatus(status);
        task.setSourceType(TaskSourceType.OPENAPI);
        task.setSourceRef("source-" + taskId);
        task.setTargetApiSpecIds(List.of("api-" + taskId));
        task.setPromotionMode(promotionMode);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase6");
        task.setMetadata(metadata);
        return tasks.save(task);
    }

    private TestCaseDraft draft(String taskId, String draftId) {
        var draft = new TestCaseDraft();
        draft.setDraftId(draftId);
        draft.setTaskId(taskId);
        draft.setSource(CaseSource.STRUCTURE);
        draft.setStage(CaseSource.STRUCTURE);
        draft.setStatus(DraftStatus.PENDING_REVIEW);
        draft.setPromotionMode(PromotionMode.MANUAL);
        draft.setTargetApiSpecId("api-" + taskId);
        draft.setDedupKey("dedup-" + draftId);
        draft.setExpectedStatusCode(200);
        draft.setDraftContent(Map.of(
            "title", "Generated " + draftId,
            "description", "Generated draft for memory candidate handoff",
            "expectedResult", "HTTP 200",
            "steps", List.of(Map.of("name", "Call API", "expected", "OK")),
            "tags", List.of("phase6")
        ));
        return draft;
    }

    private List<Map<String, Object>> metadataRecords(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .map(this::metadataMap)
            .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataMap(Object value) {
        return (Map<String, Object>) value;
    }
}
