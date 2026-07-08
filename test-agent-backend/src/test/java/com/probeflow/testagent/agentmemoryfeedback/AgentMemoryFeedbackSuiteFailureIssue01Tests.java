package com.probeflow.testagent.agentmemoryfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.failureanalysis.FailureClassification;
import com.probeflow.testagent.failureanalysis.SuiteFailureAnalysis;
import com.probeflow.testagent.failureanalysis.SuiteFailureStep;
import com.probeflow.testagent.failureanalysis.SuiteVariableFailure;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
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
class AgentMemoryFeedbackSuiteFailureIssue01Tests {

    @Autowired
    private AgentMemoryFeedbackApplicationService memoryFeedback;

    @Autowired
    private MemoryCandidateRecordRepository candidates;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private EntityManager entityManager;

    @Test
    void variableExtractionSuiteFailureBecomesRedactedIdempotentRefinedMemoryCandidate() {
        saveTask("task-v3-6-suite-issue-01");
        var request = extractionFailureRequest("task-v3-6-suite-issue-01");

        var first = memoryFeedback.refineSuiteFailureAnalysisCandidate(request);
        var second = memoryFeedback.refineSuiteFailureAnalysisCandidate(request);

        entityManager.flush();
        entityManager.clear();

        assertThat(first.status()).isIn(MemoryCandidateProcessingStatus.ACCEPTED, MemoryCandidateProcessingStatus.MERGED);
        assertThat(first.memoryId()).isNotBlank();
        assertThat(first.auditSummary())
            .containsEntry("sourceType", AgentMemoryCandidateSourceType.FAILURE_ANALYSIS.name())
            .containsEntry("refineryInvoked", true)
            .containsEntry("writesLongTermMemory", true);

        assertThat(second.status()).isEqualTo(MemoryCandidateProcessingStatus.DUPLICATE);
        assertThat(second.candidateId()).isEqualTo(first.candidateId());
        assertThat(second.auditSummary()).containsEntry("idempotent", true);

        var record = candidates.findById(first.candidateId()).orElseThrow();
        assertThat(record.getSourceType()).isEqualTo(AgentMemoryCandidateSourceType.FAILURE_ANALYSIS);
        assertThat(record.getSourceRef())
            .startsWith("suite-failure-analysis:exec-v3-6-issue-01:VARIABLE_EXTRACTION_FAILURE:");
        assertThat(record.getSummary())
            .contains("V3 SUITE VARIABLE_EXTRACTION_FAILURE")
            .contains("create-order");
        assertThat(record.getContent())
            .contains("Failure type: VARIABLE_EXTRACTION_FAILURE")
            .contains("failed variable orderId")
            .contains("extractRule rule-order-id")
            .contains("pay-order")
            .contains("query-order")
            .doesNotContain("secret-order-token");
        assertThat(record.getSanitizedEvidence())
            .contains("rootStep")
            .contains("affectedDownstreamSteps")
            .contains("sourcePath=$.data.orderId")
            .contains("nextSuggestion")
            .doesNotContain("secret-order-token")
            .contains(MemoryFeedbackSanitizer.MASKED_VALUE);
        assertThat(record.getTags()).contains(
            "v3",
            "suite",
            "failure-analysis",
            "memory-feedback",
            "variable-extraction"
        );
        assertThat(record.getMetadata())
            .containsEntry("suiteId", "suite-order-checkout")
            .containsEntry("caseId", "case-order-checkout")
            .containsEntry("executionId", "exec-v3-6-issue-01")
            .containsEntry("failureClassification", "VARIABLE_EXTRACTION_FAILURE")
            .containsEntry("recoveryActionType", "FIX_EXTRACT_RULE")
            .containsEntry("requiresHumanReview", false);
        assertThat(((Number) record.getMetadata().get("confidence")).doubleValue()).isEqualTo(0.91d);
        assertThat(record.getMetadata().toString()).doesNotContain("secret-order-token");
        assertThat(record.getRefineryResultSummary()).containsEntry("refineryInvoked", true);
        assertThat(longTermMemories.findById(first.memoryId()).orElseThrow().getTags())
            .contains("v3", "suite", "variable-extraction");
    }

    @Test
    void rejectsSuiteFailureCandidateMissingTaskSourceRefConfidenceOrCriticalEvidence() {
        var result = memoryFeedback.refineSuiteFailureAnalysisCandidate(new SuiteFailureMemoryFeedbackRequest(
            " ",
            "suite-order-checkout",
            "case-order-checkout",
            "",
            new SuiteFailureAnalysis(
                true,
                FailureClassification.VARIABLE_EXTRACTION_FAILURE,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                3,
                2,
                2,
                null
            ),
            "",
            "FIX_EXTRACT_RULE",
            false,
            null,
            List.of(),
            Map.of()
        ));

        assertThat(result.status()).isEqualTo(MemoryCandidateProcessingStatus.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo("invalid-suite-failure-analysis-candidate");
        assertThat(result.blockers())
            .contains("taskId is required")
            .contains("executionId is required")
            .contains("root cause step evidence is required")
            .contains("suite failure evidence is required")
            .contains("nextSuggestion is required")
            .contains("confidence must be between 0.0 and 1.0");
        assertThat(result.auditSummary()).containsEntry("writesLongTermMemory", false);
    }

    private SuiteFailureMemoryFeedbackRequest extractionFailureRequest(String taskId) {
        var createOrder = new SuiteFailureStep(
            "create-order",
            "Create order",
            1,
            "api-order-create",
            "FAILED",
            200,
            "Response did not expose order id",
            null
        );
        var payOrder = new SuiteFailureStep(
            "pay-order",
            "Pay order",
            2,
            "api-order-pay",
            "SKIPPED",
            null,
            null,
            "Blocked by missing suite.orderId"
        );
        var queryOrder = new SuiteFailureStep(
            "query-order",
            "Query order",
            3,
            "api-order-query",
            "SKIPPED",
            null,
            null,
            "Blocked by missing suite.orderId"
        );
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
            "Required extractRule did not find orderId in create-order response.",
            "Authorization: Bearer secret-order-token",
            null
        );
        var analysis = new SuiteFailureAnalysis(
            true,
            FailureClassification.VARIABLE_EXTRACTION_FAILURE,
            createOrder,
            createOrder,
            createOrder,
            List.of(payOrder, queryOrder),
            List.of(payOrder, queryOrder),
            variableFailure,
            List.of(variableFailure),
            null,
            3,
            2,
            2,
            "create-order failed to produce suite.orderId and blocked payment/query."
        );
        return new SuiteFailureMemoryFeedbackRequest(
            taskId,
            "suite-order-checkout",
            "case-order-checkout",
            "exec-v3-6-issue-01",
            analysis,
            "Check response field path, extractRule source mapping, and upstream response shape before rerunning downstream steps.",
            "FIX_EXTRACT_RULE",
            false,
            0.91f,
            List.of(
                "diagnostic=EXTRACT_RULE_REQUIRED_VALUE_MISSING sourcePath=$.data.orderId",
                "authorization=Bearer secret-order-token"
            ),
            Map.of(
                "providerMode", "DETERMINISTIC_FAKE",
                "fixtureId", "order-suite-variable-extraction-failure",
                "token", "secret-order-token"
            )
        );
    }

    private Task saveTask(String taskId) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("V3-6 suite memory feedback " + taskId);
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("v3-6-issue-01");
        task.setTargetApiSpecIds(List.of("api-order-create", "api-order-pay", "api-order-query"));
        task.setPromotionMode(PromotionMode.AUTO);
        task.setPriority(TaskPriority.HIGH);
        task.setCreator("v3-6-test");
        task.setMetadata(Map.of());
        return tasks.save(task);
    }
}
