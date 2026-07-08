package com.probeflow.testagent.agentmemoryfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.failureanalysis.FailureClassification;
import com.probeflow.testagent.failureanalysis.SuiteDependencyFailure;
import com.probeflow.testagent.failureanalysis.SuiteFailureAnalysis;
import com.probeflow.testagent.failureanalysis.SuiteFailureStep;
import com.probeflow.testagent.failureanalysis.SuiteVariableFailure;
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
class AgentMemoryFeedbackSuiteFailureIssue02Tests {

    @Autowired
    private AgentMemoryFeedbackApplicationService memoryFeedback;

    @Autowired
    private MemoryCandidateRecordRepository candidates;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private EntityManager entityManager;

    @Test
    void variableResolutionDependencyOrderAndBusinessPreconditionFailuresCreateScenarioSpecificCandidates() {
        saveTask("task-v3-6-issue-02-resolution");
        saveTask("task-v3-6-issue-02-order");
        saveTask("task-v3-6-issue-02-precondition");

        var resolution = memoryFeedback.refineSuiteFailureAnalysisCandidate(variableResolutionRequest());
        var dependency = memoryFeedback.refineSuiteFailureAnalysisCandidate(dependencyOrderRequest());
        var precondition = memoryFeedback.refineSuiteFailureAnalysisCandidate(businessPreconditionRequest());

        entityManager.flush();
        entityManager.clear();

        assertThat(resolution.status()).isIn(MemoryCandidateProcessingStatus.ACCEPTED, MemoryCandidateProcessingStatus.MERGED);
        assertRecord(resolution, "variable-resolution")
            .satisfies(record -> {
                assertThat(record.getContent())
                    .contains("pay-order")
                    .contains("${suite.orderId}")
                    .contains("request.path")
                    .contains("Fix the variable reference");
                assertThat(record.getMetadata().toString()).contains("missingOrderId");
            });

        assertThat(dependency.status()).isIn(MemoryCandidateProcessingStatus.ACCEPTED, MemoryCandidateProcessingStatus.MERGED);
        assertRecord(dependency, "dependency-order")
            .satisfies(record -> {
                assertThat(record.getContent())
                    .contains("producer create-order order 2")
                    .contains("consumer pay-order order 1")
                    .contains("orderId")
                    .contains("Move the producer step before the consumer");
                assertThat(record.getTags()).doesNotContain("variable-extraction");
            });

        assertThat(precondition.status()).isIn(MemoryCandidateProcessingStatus.ACCEPTED, MemoryCandidateProcessingStatus.MERGED);
        assertRecord(precondition, "business-precondition")
            .satisfies(record -> assertThat(record.getContent())
                .contains("Business precondition evidence")
                .contains("ORDER_CONFIRMED")
                .contains("confirm-order")
                .contains("confirmed order test data"));
    }

    @Test
    void downstreamLowConfidenceAndHighRiskCandidatesDoNotPolluteDependencyOrLongTermMemoryPaths() {
        saveTask("task-v3-6-issue-02-downstream");
        saveTask("task-v3-6-issue-02-low-confidence");
        saveTask("task-v3-6-issue-02-high-risk");

        var downstream = memoryFeedback.refineSuiteFailureAnalysisCandidate(downstreamApiRequest());
        var lowConfidence = memoryFeedback.refineSuiteFailureAnalysisCandidate(lowConfidenceRequest());
        var highRisk = memoryFeedback.refineSuiteFailureAnalysisCandidate(highRiskRequest());

        entityManager.flush();
        entityManager.clear();

        assertThat(downstream.status()).isIn(MemoryCandidateProcessingStatus.ACCEPTED, MemoryCandidateProcessingStatus.MERGED);
        assertRecord(downstream, "downstream-api")
            .satisfies(record -> {
                assertThat(record.getTags()).contains("downstream-api");
                assertThat(record.getTags()).doesNotContain("dependency-order", "business-precondition", "variable-resolution");
                assertThat(record.getContent())
                    .contains("Downstream API evidence")
                    .contains("not as a variable, precondition, or step-order fix");
            });

        assertThat(lowConfidence.status()).isEqualTo(MemoryCandidateProcessingStatus.PENDING);
        assertThat(lowConfidence.memoryId()).isNull();
        assertRecord(lowConfidence, "variable-resolution")
            .satisfies(record -> assertThat(record.getAuditSummary())
                .containsEntry("writesLongTermMemory", false)
                .containsEntry("refineryInvoked", false));

        assertThat(highRisk.status()).isEqualTo(MemoryCandidateProcessingStatus.PENDING);
        assertThat(highRisk.memoryId()).isNull();
        assertRecord(highRisk, "business-precondition")
            .satisfies(record -> {
                assertThat(record.getMetadata())
                    .containsEntry("requiresHumanReview", true)
                    .containsEntry("recoveryActionType", "WAIT_FOR_HUMAN");
                assertThat(record.getAuditSummary())
                    .containsEntry("writesLongTermMemory", false)
                    .containsEntry("refineryInvoked", false);
            });
    }

    private org.assertj.core.api.AbstractObjectAssert<?, MemoryCandidateRecord> assertRecord(
        AgentMemoryFeedbackResult result,
        String tag
    ) {
        var record = candidates.findById(result.candidateId()).orElseThrow();
        assertThat(record.getTags()).contains(tag, "v3", "suite", "memory-feedback");
        assertThat(record.getSanitizedEvidence()).contains("classification");
        assertThat(record.getMetadata())
            .containsKey("failureClassification")
            .containsKey("rootStepId")
            .containsKey("affectedDownstreamStepIds")
            .containsKey("nextSuggestion");
        return assertThat(record);
    }

    private SuiteFailureMemoryFeedbackRequest variableResolutionRequest() {
        return new SuiteFailureMemoryFeedbackRequest(
            "task-v3-6-issue-02-resolution",
            "suite-order",
            "case-order",
            "exec-v3-6-resolution",
            analysis(
                FailureClassification.VARIABLE_RESOLUTION_FAILURE,
                step("pay-order", 2, "FAILED", 0),
                List.of(step("query-order", 3, "SKIPPED", null)),
                new SuiteVariableFailure(
                    FailureClassification.VARIABLE_RESOLUTION_FAILURE,
                    "pay-order",
                    "${suite.orderId}",
                    "request.path",
                    "suite",
                    "missingOrderId",
                    null,
                    null,
                    null,
                    "suite",
                    "missingOrderId",
                    "Consumer references ${suite.orderId} before it exists.",
                    null,
                    null
                ),
                null,
                "pay-order references missing suite.orderId"
            ),
            "Fix the variable reference or ensure create-order writes suite.orderId before pay-order.",
            "FIX_VARIABLE_REFERENCE",
            false,
            0.84f,
            List.of("diagnostic=VARIABLE_RESOLUTION_FAILED expression=${suite.orderId} location=request.path"),
            Map.of("providerMode", "DETERMINISTIC_FAKE")
        );
    }

    private SuiteFailureMemoryFeedbackRequest dependencyOrderRequest() {
        return new SuiteFailureMemoryFeedbackRequest(
            "task-v3-6-issue-02-order",
            "suite-order",
            "case-order",
            "exec-v3-6-order",
            analysis(
                FailureClassification.DEPENDENCY_ORDER_FAILURE,
                step("pay-order", 1, "FAILED", 0),
                List.of(step("query-order", 3, "SKIPPED", null)),
                null,
                new SuiteDependencyFailure(
                    "create-order",
                    2,
                    "pay-order",
                    1,
                    "${suite.orderId}",
                    "suite",
                    "orderId",
                    "orderId",
                    "Consumer step is ordered before producer step."
                ),
                "pay-order runs before create-order can produce orderId"
            ),
            "Move the producer step before the consumer and keep the orderId dependency explicit.",
            "REORDER_STEPS",
            false,
            0.86f,
            List.of("diagnostic=DEPENDENCY_ORDER_FAILURE producer=create-order consumer=pay-order"),
            Map.of("providerMode", "DETERMINISTIC_FAKE")
        );
    }

    private SuiteFailureMemoryFeedbackRequest businessPreconditionRequest() {
        return new SuiteFailureMemoryFeedbackRequest(
            "task-v3-6-issue-02-precondition",
            "suite-order",
            "case-order",
            "exec-v3-6-precondition",
            analysis(
                FailureClassification.BUSINESS_PRECONDITION_FAILURE,
                step("pay-order", 2, "FAILED", 409),
                List.of(step("query-order", 3, "SKIPPED", null)),
                null,
                null,
                "payment requires confirmed order state"
            ),
            "Add a confirm-order prerequisite or seed confirmed order test data before payment.",
            "ADD_PRECONDITION_STEP",
            false,
            0.80f,
            List.of("diagnostic=BUSINESS_PRECONDITION status=ORDER_CREATED expected=ORDER_CONFIRMED"),
            Map.of(
                "businessState", "ORDER_CONFIRMED",
                "missingPreconditionStep", "confirm-order",
                "testDataRequirement", "confirmed order test data"
            )
        );
    }

    private SuiteFailureMemoryFeedbackRequest downstreamApiRequest() {
        return new SuiteFailureMemoryFeedbackRequest(
            "task-v3-6-issue-02-downstream",
            "suite-order",
            "case-order",
            "exec-v3-6-downstream",
            analysis(
                FailureClassification.DOWNSTREAM_API_FAILURE,
                step("pay-order", 2, "FAILED", 503),
                List.of(step("query-order", 3, "SKIPPED", null)),
                null,
                null,
                "payment service returned 503 after variables resolved"
            ),
            "Inspect downstream payment API health; prerequisite variables were already resolved.",
            "CHECK_DOWNSTREAM_API",
            false,
            0.82f,
            List.of("diagnostic=DOWNSTREAM_API_FAILURE statusCode=503 variablesResolved=true"),
            Map.of("providerMode", "DETERMINISTIC_FAKE")
        );
    }

    private SuiteFailureMemoryFeedbackRequest lowConfidenceRequest() {
        return new SuiteFailureMemoryFeedbackRequest(
            "task-v3-6-issue-02-low-confidence",
            "suite-order",
            "case-order",
            "exec-v3-6-low-confidence",
            variableResolutionRequest().suiteFailureAnalysis(),
            "Needs human confirmation before learning.",
            "FIX_VARIABLE_REFERENCE",
            false,
            0.42f,
            List.of("diagnostic=LOW_CONFIDENCE_VARIABLE_RESOLUTION"),
            Map.of("providerMode", "DETERMINISTIC_FAKE")
        );
    }

    private SuiteFailureMemoryFeedbackRequest highRiskRequest() {
        return new SuiteFailureMemoryFeedbackRequest(
            "task-v3-6-issue-02-high-risk",
            "suite-order",
            "case-order",
            "exec-v3-6-high-risk",
            businessPreconditionRequest().suiteFailureAnalysis(),
            "Ask a human to confirm before learning or replanning this business precondition.",
            "WAIT_FOR_HUMAN",
            true,
            0.88f,
            List.of("diagnostic=HIGH_RISK_RECOVERY requiresHumanReview=true"),
            Map.of("businessState", "PAYMENT_LOCKED", "highRiskRecovery", true)
        );
    }

    private SuiteFailureAnalysis analysis(
        FailureClassification classification,
        SuiteFailureStep root,
        List<SuiteFailureStep> affected,
        SuiteVariableFailure variable,
        SuiteDependencyFailure dependency,
        String impact
    ) {
        return new SuiteFailureAnalysis(
            true,
            classification,
            root,
            root,
            root,
            affected,
            affected,
            variable,
            variable == null ? List.of() : List.of(variable),
            dependency,
            3,
            affected.size(),
            affected.size(),
            impact
        );
    }

    private SuiteFailureStep step(String stepId, int order, String status, Integer statusCode) {
        return new SuiteFailureStep(stepId, stepId, order, "api-" + stepId, status, statusCode, status, null);
    }

    private Task saveTask(String taskId) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("V3-6 suite memory feedback " + taskId);
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("v3-6-issue-02");
        task.setTargetApiSpecIds(List.of("api-order-create", "api-order-pay", "api-order-query"));
        task.setPromotionMode(PromotionMode.AUTO);
        task.setPriority(TaskPriority.HIGH);
        task.setCreator("v3-6-test");
        task.setMetadata(Map.of());
        return tasks.save(task);
    }
}
