package com.probeflow.testagent.agentmemoryfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.humanintheloop.HumanDecisionRecord;
import com.probeflow.testagent.humanintheloop.HumanDecisionSubmissionRequest;
import com.probeflow.testagent.humanintheloop.HumanDecisionType;
import com.probeflow.testagent.humanintheloop.HumanDraftReviewDecisionStatus;
import com.probeflow.testagent.humanintheloop.HumanDraftReviewRequest;
import com.probeflow.testagent.humanintheloop.HumanHighRiskDecisionStatus;
import com.probeflow.testagent.humanintheloop.HumanInTheLoopApplicationService;
import com.probeflow.testagent.humanintheloop.HumanInputResolutionStatus;
import com.probeflow.testagent.humanintheloop.HumanPlannerClarificationStatus;
import com.probeflow.testagent.humanintheloop.HumanRequestType;
import com.probeflow.testagent.humanintheloop.HumanReviewRequestCreateRequest;
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
class AgentMemoryFeedbackHumanDecisionRefineryTests {

    @Autowired
    private AgentMemoryFeedbackApplicationService memoryFeedback;

    @Autowired
    private HumanInTheLoopApplicationService humanInTheLoop;

    @Autowired
    private MemoryCandidateRecordRepository candidates;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TestCaseDraftRepository drafts;

    @Autowired
    private EntityManager entityManager;

    @Test
    void draftReviewPromoteDiscardAndRequestChangesEnterMemoryRefinery() {
        var promote = applyDraftDecision(
            "task-phase7-promote",
            "draft-phase7-promote",
            HumanDecisionType.PROMOTE_DRAFT,
            "Promote because the generated happy path is reusable.",
            Map.of("reviewDecision", "promote", "draftIds", List.of("draft-phase7-promote"))
        );
        var discard = applyDraftDecision(
            "task-phase7-discard",
            "draft-phase7-discard",
            HumanDecisionType.DISCARD_DRAFT,
            "Discard because it duplicates existing tenant coverage.",
            Map.of("reviewDecision", "discard", "draftIds", List.of("draft-phase7-discard"))
        );
        var changes = applyDraftDecision(
            "task-phase7-changes",
            "draft-phase7-changes",
            HumanDecisionType.REQUEST_CHANGES,
            "Needs retry assertion before promotion.",
            Map.of(
                "reviewDecision", "request_changes",
                "draftIds", List.of("draft-phase7-changes"),
                "changeRequest", "Add timeout and retry assertions before promotion."
            )
        );

        var promoteResult = memoryFeedback.refineHumanDecisionCandidate(promote.getDecisionId());
        var discardResult = memoryFeedback.refineHumanDecisionCandidate(discard.getDecisionId());
        var changesResult = memoryFeedback.refineHumanDecisionCandidate(changes.getDecisionId());

        entityManager.flush();
        entityManager.clear();

        assertRefinedHumanDecision(promoteResult, promote, "testing-pattern");
        assertRefinedHumanDecision(discardResult, discard, "testing-pattern");
        assertRefinedHumanDecision(changesResult, changes, "generation-preference");
        assertThat(longTermMemories.findAll()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void blockerClarificationAndHighRiskRejectionAreRefinedWithSensitivePayloadsMasked() {
        var blocker = applyBlockerResolution();
        var clarification = applyPlannerClarification();
        var highRisk = applyHighRiskRejection();

        var blockerResult = memoryFeedback.refineHumanDecisionCandidate(blocker.getDecisionId());
        var clarificationResult = memoryFeedback.refineHumanDecisionCandidate(clarification.getDecisionId());
        var highRiskResult = memoryFeedback.refineHumanDecisionCandidate(highRisk.getDecisionId());

        entityManager.flush();
        entityManager.clear();

        assertRefinedHumanDecision(blockerResult, blocker, "blocker-resolution");
        assertRefinedHumanDecision(clarificationResult, clarification, "planner-clarification");
        assertRefinedHumanDecision(highRiskResult, highRisk, "policy-learning");
        assertMemoryDoesNotContainSecret(blockerResult.memoryId(), "secret-blocker-token");
        assertMemoryDoesNotContainSecret(clarificationResult.memoryId(), "secret-clarification-token");
        assertMemoryDoesNotContainSecret(highRiskResult.memoryId(), "secret-high-risk-token");
    }

    @Test
    void repeatingSameHumanDecisionIsIdempotentAndDoesNotCreateAnotherLongTermMemory() {
        var blocker = applyBlockerResolution();

        var first = memoryFeedback.refineHumanDecisionCandidate(blocker.getDecisionId());
        var memoryCountAfterFirst = longTermMemories.count();
        var second = memoryFeedback.refineHumanDecisionCandidate(blocker.getDecisionId());

        assertThat(first.status()).isIn(MemoryCandidateProcessingStatus.ACCEPTED, MemoryCandidateProcessingStatus.MERGED);
        assertThat(second.status()).isEqualTo(MemoryCandidateProcessingStatus.DUPLICATE);
        assertThat(second.candidateId()).isEqualTo(first.candidateId());
        assertThat(longTermMemories.count()).isEqualTo(memoryCountAfterFirst);
        assertThat(candidates.findAllByTaskIdOrderByCreatedAtAscCandidateIdAsc(blocker.getTaskId()))
            .hasSize(1);
    }

    private void assertRefinedHumanDecision(
        AgentMemoryFeedbackResult result,
        HumanDecisionRecord decision,
        String expectedTag
    ) {
        assertThat(result.status()).isIn(MemoryCandidateProcessingStatus.ACCEPTED, MemoryCandidateProcessingStatus.MERGED);
        assertThat(result.memoryId()).isNotBlank();
        var record = candidates.findById(result.candidateId()).orElseThrow();
        assertThat(record.getSourceType()).isEqualTo(AgentMemoryCandidateSourceType.HUMAN_DECISION_RECORD);
        assertThat(record.getSourceRef()).isEqualTo("human-decision:" + decision.getDecisionId());
        assertThat(record.getStatus()).isEqualTo(result.status());
        assertThat(record.getRefineryResultSummary()).containsEntry("refineryInvoked", true);
        assertThat(record.getAuditSummary()).containsEntry("writesLongTermMemory", true);

        var memory = longTermMemories.findById(result.memoryId()).orElseThrow();
        assertThat(memory.getSourceType()).isEqualTo(MemorySourceType.USER_FEEDBACK);
        assertThat(memory.getConfidence()).isGreaterThanOrEqualTo(0.84f);
        assertThat(memory.getTags()).contains("human-feedback", expectedTag);
        assertThat(memory.getMetadata())
            .containsEntry("phase", "V2_PHASE_7")
            .containsEntry("writesLongTermMemory", true);
        assertThat(memory.getMetadata().toString())
            .contains("human-decision:" + decision.getDecisionId());
    }

    private void assertMemoryDoesNotContainSecret(String memoryId, String secret) {
        var memory = longTermMemories.findById(memoryId).orElseThrow();
        assertThat(memory.getSummary()).doesNotContain(secret);
        assertThat(memory.getContent()).doesNotContain(secret);
        assertThat(memory.getFullContent()).doesNotContain(secret);
        assertThat(memory.getMetadata().toString()).doesNotContain(secret);
        assertThat(memory.getFullContent()).contains(MemoryFeedbackSanitizer.MASKED_VALUE);
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
            "step-" + taskId,
            "Manual review is required before execution."
        )).request();
        var result = humanInTheLoop.applyDraftReviewDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            decisionType,
            "reviewer-phase7",
            reason,
            payload
        ));
        assertThat(result.status()).isEqualTo(HumanDraftReviewDecisionStatus.APPLIED);
        return result.decision();
    }

    private HumanDecisionRecord applyBlockerResolution() {
        var task = saveTask(
            "task-phase7-blocker",
            TaskStatus.WAITING_FOR_REVIEW,
            PromotionMode.AUTO,
            Map.of("lastReplanning", Map.of("resumeTaskStatus", TaskStatus.EXECUTING.name()))
        );
        var request = humanInTheLoop.createRequest(new HumanReviewRequestCreateRequest(
            task.getTaskId(),
            "step-phase7-blocker",
            HumanRequestType.MISSING_INPUT,
            "Missing execution input for target environment.",
            List.of(
                Map.of("name", "baseUrl", "type", "string", "required", true),
                Map.of("name", "environment", "type", "string", "required", true),
                Map.of("name", "token", "type", "string", "required", false)
            ),
            ToolRiskLevel.MEDIUM,
            ReplanningTrigger.EXECUTION_READINESS_MISSING.name(),
            "decision-phase7-blocker",
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

    private HumanDecisionRecord applyPlannerClarification() {
        var task = saveTask("task-phase7-clarification", TaskStatus.WAITING_FOR_REVIEW, PromotionMode.AUTO, Map.of());
        var request = humanInTheLoop.createRequest(new HumanReviewRequestCreateRequest(
            task.getTaskId(),
            "step-phase7-clarification",
            HumanRequestType.PLANNER_CLARIFICATION,
            "Planner needs a target environment before recovery.",
            List.of(
                Map.of("name", "targetEnvironment", "type", "string", "required", true),
                Map.of("name", "token", "type", "string", "required", false)
            ),
            ToolRiskLevel.HIGH,
            ReplanningTrigger.PLAN_STEP_FAILED.name(),
            "decision-phase7-clarification",
            PolicyValidationReasonCode.HUMAN_INPUT_REQUIRED.name(),
            Map.of("question", "Which configured environment should the next suggested step use?"),
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

    private HumanDecisionRecord applyHighRiskRejection() {
        var task = saveTask("task-phase7-high-risk", TaskStatus.WAITING_FOR_REVIEW, PromotionMode.AUTO, Map.of());
        var request = humanInTheLoop.createRequest(new HumanReviewRequestCreateRequest(
            task.getTaskId(),
            "step-phase7-high-risk",
            HumanRequestType.HIGH_RISK_APPROVAL,
            "Planner decision is high risk and requires human confirmation.",
            List.of(
                Map.of("name", "approved", "type", "boolean", "required", true),
                Map.of("name", "apiToken", "type", "string", "required", false)
            ),
            ToolRiskLevel.HIGH,
            ReplanningTrigger.FAILURE_ANALYSIS_HIGH_RISK.name(),
            "decision-phase7-high-risk",
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

    private Task saveTask(String taskId, TaskStatus status, PromotionMode promotionMode, Map<String, Object> metadata) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Human memory feedback " + taskId);
        task.setStatus(status);
        task.setSourceType(TaskSourceType.OPENAPI);
        task.setSourceRef("source-" + taskId);
        task.setTargetApiSpecIds(List.of("api-" + taskId));
        task.setPromotionMode(promotionMode);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase7");
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
            "description", "Generated draft for memory candidate refinery",
            "expectedResult", "HTTP 200",
            "steps", List.of(Map.of("name", "Call API", "expected", "OK")),
            "tags", List.of("phase7")
        ));
        return draft;
    }
}
