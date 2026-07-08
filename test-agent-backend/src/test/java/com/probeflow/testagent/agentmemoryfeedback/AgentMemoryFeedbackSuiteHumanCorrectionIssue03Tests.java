package com.probeflow.testagent.agentmemoryfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.humanintheloop.HumanDecisionRecord;
import com.probeflow.testagent.humanintheloop.HumanDecisionSubmissionRequest;
import com.probeflow.testagent.humanintheloop.HumanDecisionSubmissionStatus;
import com.probeflow.testagent.humanintheloop.HumanDecisionType;
import com.probeflow.testagent.humanintheloop.HumanInTheLoopApplicationService;
import com.probeflow.testagent.humanintheloop.HumanRequestType;
import com.probeflow.testagent.humanintheloop.HumanReviewRequestCreateRequest;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.replanning.ReplanningTrigger;
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
class AgentMemoryFeedbackSuiteHumanCorrectionIssue03Tests {

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
    private EntityManager entityManager;

    @Test
    void suiteReviewCorrectionsCreateStructuredHumanConfirmedMemoryCandidates() {
        var extractRule = suiteCorrectionDecision(
            "extract-rule",
            HumanDecisionType.REQUEST_CHANGES,
            Map.of(
                "oldSourcePath", "$.data.id",
                "newSourcePath", "$.data.order.id",
                "targetScope", "suite",
                "targetKey", "orderId",
                "producerStepId", "create-order",
                "applicableWhen", "order creation response nests identifiers under data.order",
                "token", "secret-extract-token"
            )
        );
        var variableReference = suiteCorrectionDecision(
            "variable-reference",
            HumanDecisionType.REQUEST_CHANGES,
            Map.of(
                "oldExpression", "${suite.id}",
                "newExpression", "${suite.orderId}",
                "consumerStepId", "pay-order",
                "producerStepId", "create-order",
                "scope", "suite",
                "path", "orderId"
            )
        );
        var stepOrder = suiteCorrectionDecision(
            "step-order",
            HumanDecisionType.REQUEST_CHANGES,
            Map.of(
                "oldOrder", "pay-order before create-order",
                "newOrder", "create-order before pay-order",
                "producerStepId", "create-order",
                "consumerStepId", "pay-order",
                "businessReason", "Payment requires an existing order"
            )
        );
        var precondition = suiteCorrectionDecision(
            "business-precondition",
            HumanDecisionType.PROVIDE_INPUT,
            Map.of(
                "prerequisiteState", "ORDER_CONFIRMED",
                "prerequisiteStepId", "confirm-order",
                "testDataRequirement", "confirmed order fixture",
                "businessFlow", "order-payment"
            )
        );

        var extractResult = memoryFeedback.refineHumanDecisionCandidate(extractRule.getDecisionId());
        var referenceResult = memoryFeedback.refineHumanDecisionCandidate(variableReference.getDecisionId());
        var orderResult = memoryFeedback.refineHumanDecisionCandidate(stepOrder.getDecisionId());
        var preconditionResult = memoryFeedback.refineHumanDecisionCandidate(precondition.getDecisionId());

        entityManager.flush();
        entityManager.clear();

        assertSuiteCorrection(extractResult, extractRule, "extract-rule")
            .satisfies(record -> {
                assertThat(record.getContent())
                    .contains("$.data.id", "$.data.order.id", "suite", "orderId", "create-order");
                assertThat(record.getContent()).doesNotContain("secret-extract-token");
                assertThat(record.getMetadata().toString()).contains(MemoryFeedbackSanitizer.MASKED_VALUE);
                assertThat(record.getSanitizedEvidence()).contains(MemoryFeedbackSanitizer.MASKED_VALUE);
            });
        assertSuiteCorrection(referenceResult, variableReference, "variable-reference")
            .satisfies(record -> assertThat(record.getContent())
                .contains("${suite.id}", "${suite.orderId}", "pay-order", "create-order", "suite/orderId"));
        assertSuiteCorrection(orderResult, stepOrder, "step-order")
            .satisfies(record -> assertThat(record.getContent())
                .contains("pay-order before create-order", "create-order before pay-order", "Payment requires an existing order"));
        assertSuiteCorrection(preconditionResult, precondition, "business-precondition")
            .satisfies(record -> assertThat(record.getContent())
                .contains("ORDER_CONFIRMED", "confirm-order", "confirmed order fixture", "order-payment"));
        assertThat(longTermMemories.findAll()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void rejectedSuiteCandidateReasonIsLearnedWithoutBypassingIdempotencyOrRefinery() {
        var rejection = suiteCorrectionDecision(
            "candidate-rejection",
            HumanDecisionType.REJECT,
            Map.of(
                "candidateSourceRef", "suite-failure-analysis:exec-03:VARIABLE_RESOLUTION_FAILURE:abc",
                "rejectionReason", "The analysis blamed ${suite.orderId}, but the downstream sandbox returned stale data.",
                "reviewerIntent", "suppress similar low-quality variable blame"
            )
        );

        var first = memoryFeedback.refineHumanDecisionCandidate(rejection.getDecisionId());
        var memoryCountAfterFirst = longTermMemories.count();
        var second = memoryFeedback.refineHumanDecisionCandidate(rejection.getDecisionId());

        entityManager.flush();
        entityManager.clear();

        assertThat(first.status()).isIn(MemoryCandidateProcessingStatus.ACCEPTED, MemoryCandidateProcessingStatus.MERGED);
        assertThat(second.status()).isEqualTo(MemoryCandidateProcessingStatus.DUPLICATE);
        assertThat(second.candidateId()).isEqualTo(first.candidateId());
        assertThat(longTermMemories.count()).isEqualTo(memoryCountAfterFirst);
        assertSuiteCorrection(first, rejection, "candidate-rejection")
            .satisfies(record -> {
                assertThat(record.getTags()).contains("rejected-candidate");
                assertThat(record.getContent())
                    .contains("Rejected suite memory candidate")
                    .contains("downstream sandbox returned stale data")
                    .contains("should stay pending or be rejected");
            });
    }

    private org.assertj.core.api.AbstractObjectAssert<?, MemoryCandidateRecord> assertSuiteCorrection(
        AgentMemoryFeedbackResult result,
        HumanDecisionRecord decision,
        String correctionType
    ) {
        assertThat(result.status()).isIn(MemoryCandidateProcessingStatus.ACCEPTED, MemoryCandidateProcessingStatus.MERGED);
        assertThat(result.memoryId()).isNotBlank();
        var record = candidates.findById(result.candidateId()).orElseThrow();
        assertThat(record.getSourceType()).isEqualTo(AgentMemoryCandidateSourceType.HUMAN_DECISION_RECORD);
        assertThat(record.getSourceRef()).isEqualTo("human-decision:" + decision.getDecisionId());
        assertThat(record.getTags())
            .contains("v3", "suite", "suite-review", "suite-human-correction", "human-confirmed", correctionType);
        assertThat(record.getConfidence()).isGreaterThanOrEqualTo(0.84f);
        assertThat(record.getMetadata())
            .containsEntry("phase", "V3_PHASE_6")
            .containsEntry("originPhase", "V3_PHASE_6")
            .containsEntry("handoffType", "SUITE_HUMAN_CORRECTION_MEMORY_FEEDBACK_REFINERY")
            .containsEntry("suiteCorrectionType", correctionType)
            .containsEntry("writesLongTermMemory", true);
        assertThat(record.getAuditSummary()).containsEntry("writesLongTermMemory", true);

        var memory = longTermMemories.findById(result.memoryId()).orElseThrow();
        assertThat(memory.getSourceType()).isEqualTo(MemorySourceType.USER_FEEDBACK);
        assertThat(memory.getTags()).contains("suite-human-correction", correctionType);
        assertThat(memory.getMetadata()).containsEntry("phase", "V3_PHASE_6");
        assertThat(memory.getMetadata().toString()).contains(correctionType);
        return assertThat(record);
    }

    private HumanDecisionRecord suiteCorrectionDecision(
        String correctionType,
        HumanDecisionType decisionType,
        Map<String, Object> payload
    ) {
        var taskId = "task-v36-i03-" + switch (correctionType) {
            case "extract-rule" -> "extract";
            case "variable-reference" -> "reference";
            case "step-order" -> "order";
            case "business-precondition" -> "precond";
            case "candidate-rejection" -> "reject";
            default -> correctionType;
        };
        saveTask(taskId);
        var request = humanInTheLoop.createRequest(new HumanReviewRequestCreateRequest(
            taskId,
            "review-" + correctionType,
            HumanRequestType.DRAFT_REVIEW,
            "Review V3 SUITE correction " + correctionType,
            List.of(),
            ToolRiskLevel.LOW,
            ReplanningTrigger.PLAN_STEP_FAILED.name(),
            "suite-review-" + correctionType,
            "SUITE_REVIEW_CORRECTION",
            Map.of(
                "phase", "V3_PHASE_6",
                "suiteCorrectionType", correctionType,
                "suiteId", "suite-order",
                "caseId", "case-order",
                "reviewerIntent", payload.getOrDefault("reviewerIntent", "learn corrected suite review decision"),
                "replanningContext", Map.of("nextAction", "record-memory-only")
            ),
            null
        )).request();
        var result = humanInTheLoop.submitDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            decisionType,
            "suite-reviewer",
            "Human confirmed " + correctionType + " correction.",
            payload
        ));
        assertThat(result.status()).isEqualTo(HumanDecisionSubmissionStatus.ACCEPTED);
        return result.decision();
    }

    private Task saveTask(String taskId) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("V3-6 suite human correction " + taskId);
        task.setStatus(TaskStatus.WAITING_FOR_REVIEW);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("v3-6-issue-03");
        task.setTargetApiSpecIds(List.of("api-order-create", "api-order-pay", "api-order-query"));
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setPriority(TaskPriority.HIGH);
        task.setCreator("v3-6-test");
        task.setMetadata(Map.of());
        return tasks.save(task);
    }
}
