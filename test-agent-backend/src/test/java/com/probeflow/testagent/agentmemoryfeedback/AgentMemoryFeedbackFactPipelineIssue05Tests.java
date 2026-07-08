package com.probeflow.testagent.agentmemoryfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.ProposedPlanStep;
import com.probeflow.testagent.failureanalysis.FailureClassification;
import com.probeflow.testagent.failureanalysis.SuiteFailureAnalysis;
import com.probeflow.testagent.failureanalysis.SuiteFailureStep;
import com.probeflow.testagent.failureanalysis.SuiteVariableFailure;
import com.probeflow.testagent.humanintheloop.HumanDecisionRecord;
import com.probeflow.testagent.humanintheloop.HumanDecisionSubmissionRequest;
import com.probeflow.testagent.humanintheloop.HumanDecisionType;
import com.probeflow.testagent.humanintheloop.HumanDraftReviewDecisionStatus;
import com.probeflow.testagent.humanintheloop.HumanDraftReviewRequest;
import com.probeflow.testagent.humanintheloop.HumanInTheLoopApplicationService;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.memory.MemoryCandidateRequest;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.orchestration.StepOutcome;
import com.probeflow.testagent.policyvalidator.PolicyValidationReasonCode;
import com.probeflow.testagent.policyvalidator.PolicyValidationResult;
import com.probeflow.testagent.task.PlanStepStatus;
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
class AgentMemoryFeedbackFactPipelineIssue05Tests {

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

    @Test
    void failureAnalysisCandidateEntersFactAwareRefineryWithSanitizedRecord() {
        var taskId = "issue05-failure-analysis-task";
        saveTask(taskId, TaskStatus.ANALYZING_RESULTS, PromotionMode.AUTO);

        var result = memoryFeedback.refineFailureAnalysisCandidate(
            AgentMemoryCandidateSourceType.FAILURE_ANALYSIS,
            new MemoryCandidateRequest(
                "Payment gateway timeout requires retry fixture",
                "Failure analysis found GW_TIMEOUT on POST /api/payments/charge and recommends the approved retry fixture.",
                MemorySourceType.EXECUTION_RESULT,
                "failure-analysis:issue05-payment-timeout",
                taskId,
                List.of("payment", "timeout", "retry"),
                0.88f,
                "raw stack includes sanitized credential marker and statusCode=504",
                Map.of(
                    "systemName", "billing",
                    "module", "payment",
                    "apiPath", "/api/payments/charge",
                    "httpMethod", "POST",
                    "errorCode", "GW_TIMEOUT",
                    "riskLevel", "HIGH",
                    "retryable", true
                )
            )
        );

        var record = assertAcceptedFactRecord(result, "failure_pattern");
        assertThat(record.getSourceType()).isEqualTo(AgentMemoryCandidateSourceType.FAILURE_ANALYSIS);
        assertThat(record.getSanitizedEvidence())
            .contains("statusCode=504")
            .doesNotContain("raw stack includes token=");
        assertThat(longTermMemories.findById(result.memoryId()).orElseThrow().getMetadata())
            .containsEntry("factType", "failure_pattern")
            .containsEntry("errorCode", "GW_TIMEOUT");
    }

    @Test
    void stepOutcomeCandidatePreservesStepRiskAndRetryMetadataThroughFactPipeline() {
        var taskId = "issue05-step-outcome-task";
        saveTask(taskId, TaskStatus.ANALYZING_RESULTS, PromotionMode.AUTO);
        var outcome = new StepOutcome(
            PlanStepStatus.FAILED,
            TaskStatus.FAILED,
            "Payment execution timed out after retry.",
            List.of("execution:issue05-step"),
            "execution:issue05-step",
            List.of("timeout after 30s", "retry exhausted"),
            true
        );

        var result = memoryFeedback.refineStepOutcomeCandidate(taskId, "issue05-payment-step", outcome);

        var record = assertAcceptedFactRecord(result, "failure_pattern");
        assertThat(record.getMetadata())
            .containsEntry("stepId", "issue05-payment-step")
            .containsEntry("stepStatus", "FAILED")
            .containsEntry("riskLevel", "HIGH")
            .containsEntry("retryable", true);
        assertThat(longTermMemories.findById(result.memoryId()).orElseThrow().getMetadata())
            .containsEntry("stepId", "issue05-payment-step")
            .containsEntry("qualityStatus", "ACCEPTED");
    }

    @Test
    void humanDecisionCandidateUsesFeedbackEntryAndBoostsMemoryConfidence() {
        var decision = applyPromoteDraftDecision();

        var result = memoryFeedback.refineHumanDecisionCandidate(decision.getDecisionId());

        var record = assertAcceptedFactRecord(result, "preference");
        assertThat(record.getSourceType()).isEqualTo(AgentMemoryCandidateSourceType.HUMAN_DECISION_RECORD);
        var memory = longTermMemories.findById(result.memoryId()).orElseThrow();
        assertThat(memory.getSourceType()).isEqualTo(MemorySourceType.USER_FEEDBACK);
        assertThat(memory.getConfidence()).isGreaterThanOrEqualTo(0.86f);
        assertThat(memory.getImportance()).isGreaterThan(0.70f);
        assertThat(memory.getTags()).contains("human-feedback");
    }

    @Test
    void policyLearningNotePreservesPolicyToolAndRiskMetadataThroughFactPipeline() {
        var taskId = "issue05-policy-task";
        saveTask(taskId, TaskStatus.ANALYZING_RESULTS, PromotionMode.AUTO);
        var decision = PlanDecision.insertStep(
            "Use external.http for payment timeout recovery.",
            0.77d,
            ToolRiskLevel.HIGH,
            "external.http",
            ProposedPlanStep.of("EXECUTE_TOOL", "Call payment endpoint", "POST /api/payments/charge", "external.http")
        );
        var validation = PolicyValidationResult.blocked(
            decision,
            PolicyValidationReasonCode.TOOL_NOT_WHITELISTED,
            "Tool external.http is not whitelisted.",
            List.of("Use approved retry fixture")
        );

        var result = memoryFeedback.refinePolicyLearningNote(taskId, validation, decision, Map.of(
            "systemName", "billing",
            "module", "payment",
            "apiPath", "/api/payments/charge",
            "tags", List.of("policy", "payment")
        ));

        var record = assertAcceptedFactRecord(result, "policy_learning");
        assertThat(record.getMetadata())
            .containsEntry("policyReason", "TOOL_NOT_WHITELISTED")
            .containsEntry("toolName", "external.http")
            .containsEntry("riskLevel", "HIGH");
        assertThat(longTermMemories.findById(result.memoryId()).orElseThrow().getMetadata())
            .containsEntry("policyReason", "TOOL_NOT_WHITELISTED")
            .containsEntry("toolName", "external.http");
    }

    @Test
    void suiteFailureAnalysisPreservesSuiteEvidenceThroughFactPipeline() {
        var taskId = "issue05-suite-failure-task";
        saveTask(taskId, TaskStatus.ANALYZING_RESULTS, PromotionMode.AUTO);

        var result = memoryFeedback.refineSuiteFailureAnalysisCandidate(suiteFailureRequest(taskId));

        var record = assertAcceptedFactRecord(result, "variable_extraction_fact");
        assertThat(record.getMetadata())
            .containsEntry("executionId", "issue05-suite-exec")
            .containsEntry("suiteId", "issue05-suite")
            .containsEntry("caseId", "issue05-case")
            .containsEntry("rootStepId", "create-order")
            .containsEntry("failureClassification", "VARIABLE_EXTRACTION_FAILURE");
        assertThat((List<String>) record.getMetadata().get("affectedDownstreamStepIds"))
            .contains("pay-order");
        assertThat(longTermMemories.findById(result.memoryId()).orElseThrow().getMetadata())
            .containsEntry("failureClassification", "VARIABLE_EXTRACTION_FAILURE");
    }

    @Test
    void terminalTaskStillRejectsBeforeRefinery() {
        var taskId = "issue05-terminal-task";
        saveTask(taskId, TaskStatus.COMPLETED, PromotionMode.AUTO);

        var result = memoryFeedback.refineStepOutcomeCandidate(taskId, "terminal-step", new StepOutcome(
            PlanStepStatus.FAILED,
            TaskStatus.FAILED,
            "Timeout",
            List.of("execution:terminal"),
            "execution:terminal",
            List.of("timeout"),
            true
        ));

        assertThat(result.status()).isEqualTo(MemoryCandidateProcessingStatus.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo("terminal-task");
        assertThat(result.auditSummary()).containsEntry("writesLongTermMemory", false);
    }

    private MemoryCandidateRecord assertAcceptedFactRecord(AgentMemoryFeedbackResult result, String expectedFactType) {
        assertThat(result.status()).isIn(MemoryCandidateProcessingStatus.ACCEPTED, MemoryCandidateProcessingStatus.MERGED);
        assertThat(result.memoryId()).isNotBlank();
        var record = candidates.findById(result.candidateId()).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(result.status());
        assertThat(record.getMemoryId()).isEqualTo(result.memoryId());
        assertThat(record.getAuditSummary())
            .containsEntry("refineryInvoked", true)
            .containsEntry("writesLongTermMemory", true);
        assertThat(record.getRefineryResultSummary())
            .containsEntry("refineryInvoked", true)
            .containsEntry("accepted", true)
            .containsEntry("qualityStatus", "ACCEPTED")
            .containsEntry("factType", expectedFactType)
            .containsEntry("mergeCount", 1)
            .containsEntry("evidenceCount", 1);
        assertThat(record.getRefineryResultSummary().get("factFingerprint").toString()).startsWith("fact:");
        assertThat(record.getMetadata().toString()).doesNotContain("secret");
        assertThat(record.getContent()).doesNotContain("secret");
        return record;
    }

    private HumanDecisionRecord applyPromoteDraftDecision() {
        var task = saveTask("issue05-human-task", TaskStatus.WAITING_FOR_REVIEW, PromotionMode.MANUAL);
        drafts.save(draft(task.getTaskId(), "issue05-human-draft"));
        var request = humanInTheLoop.createDraftReviewRequest(new HumanDraftReviewRequest(
            task.getTaskId(),
            "issue05-review-step",
            "Manual review is required before execution."
        )).request();
        var result = humanInTheLoop.applyDraftReviewDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.PROMOTE_DRAFT,
            "reviewer-issue05",
            "Promote this generated payment retry test because it is reusable.",
            Map.of("reviewDecision", "promote", "draftIds", List.of("issue05-human-draft"))
        ));
        assertThat(result.status()).isEqualTo(HumanDraftReviewDecisionStatus.APPLIED);
        return result.decision();
    }

    private SuiteFailureMemoryFeedbackRequest suiteFailureRequest(String taskId) {
        var createOrder = new SuiteFailureStep("create-order", "Create order", 1, "api-create", "FAILED", 200, "Missing order id", null);
        var payOrder = new SuiteFailureStep("pay-order", "Pay order", 2, "api-pay", "SKIPPED", null, null, "Blocked by missing order id");
        var variableFailure = new SuiteVariableFailure(
            FailureClassification.VARIABLE_EXTRACTION_FAILURE,
            "create-order",
            null,
            "response.body",
            "suite",
            "orderId",
            "rule-order-id",
            "BODY_JSON",
            "$.data.orderId",
            "suite",
            "orderId",
            "Required extractRule did not find orderId.",
            null,
            null
        );
        return new SuiteFailureMemoryFeedbackRequest(
            taskId,
            "issue05-suite",
            "issue05-case",
            "issue05-suite-exec",
            new SuiteFailureAnalysis(
                true,
                FailureClassification.VARIABLE_EXTRACTION_FAILURE,
                createOrder,
                createOrder,
                createOrder,
                List.of(payOrder),
                List.of(payOrder),
                variableFailure,
                List.of(variableFailure),
                null,
                2,
                1,
                1,
                "create-order failed to produce suite.orderId."
            ),
            "Fix the response field path and rerun downstream payment step.",
            "FIX_EXTRACT_RULE",
            false,
            0.90f,
            List.of("diagnostic=EXTRACT_RULE_REQUIRED_VALUE_MISSING sourcePath=$.data.orderId"),
            Map.of("providerMode", "DETERMINISTIC_FAKE")
        );
    }

    private Task saveTask(String taskId, TaskStatus status, PromotionMode promotionMode) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Issue 05 memory feedback " + taskId);
        task.setStatus(status);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("issue05");
        task.setTargetApiSpecIds(List.of("api-issue05"));
        task.setPromotionMode(promotionMode);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("issue05-test");
        task.setMetadata(Map.of());
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
        draft.setTargetApiSpecId("api-issue05");
        draft.setDedupKey("dedup-" + draftId);
        draft.setExpectedStatusCode(200);
        draft.setDraftContent(Map.of(
            "title", "Payment retry fixture",
            "description", "Generated draft for payment retry assertions",
            "expectedResult", "HTTP 200",
            "steps", List.of(Map.of("name", "Retry payment API", "expected", "OK")),
            "tags", List.of("payment", "retry")
        ));
        return draft;
    }
}
