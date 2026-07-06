package com.probeflow.testagent.agentmemoryfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.memory.MemoryCandidateRequest;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.memory.MemoryUsageConsumer;
import com.probeflow.testagent.memory.MemoryUsageRecord;
import com.probeflow.testagent.memory.MemoryUsageRecordRepository;
import com.probeflow.testagent.memory.MemoryUsefulnessFeedbackRequest;
import com.probeflow.testagent.memory.MemoryUsefulnessFeedbackService;
import com.probeflow.testagent.memory.MemoryUsefulnessFeedbackStatus;
import com.probeflow.testagent.memory.MemoryUsefulnessOutcome;
import com.probeflow.testagent.memory.UnifiedContextBuilder;
import com.probeflow.testagent.memory.UnifiedContextQuery;
import com.probeflow.testagent.task.MemoryRefinementStatus;
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
class AgentMemoryFeedbackClosedLoopTests {

    @Autowired
    private AgentMemoryFeedbackApplicationService memoryFeedback;

    @Autowired
    private MemoryCandidateRecordRepository candidates;

    @Autowired
    private com.probeflow.testagent.memory.LongTermMemoryRepository longTermMemories;

    @Autowired
    private UnifiedContextBuilder unifiedContextBuilder;

    @Autowired
    private MemoryUsageRecordRepository usageRecords;

    @Autowired
    private MemoryUsefulnessFeedbackService usefulnessFeedback;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private EntityManager entityManager;

    @Test
    void failureAnalysisMemoryIsRecalledByNextTaskAndCalibratedByUsefulnessFeedback() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var firstTask = tasks.save(newTask("phase7-loop-first", apiSpec.getApiSpecId()));

        var candidateResult = memoryFeedback.refineFailureAnalysisCandidate(
            AgentMemoryCandidateSourceType.FAILURE_ANALYSIS,
            new MemoryCandidateRequest(
                "PAY_401 tenant bootstrap failure pattern",
                "Failure analysis learned that PAY_401 on payment execution usually means tenant bootstrap was skipped before auth.",
                MemorySourceType.EXECUTION_RESULT,
                "failure-analysis:phase7-loop-first",
                firstTask.getTaskId(),
                List.of("payment", "auth", "tenant", "PAY_401"),
                0.88f,
                "ExecutionRecord showed PAY_401 disappears after tenant bootstrap is restored.",
                Map.of(
                    "systemName", "order-platform",
                    "module", "payment",
                    "apiPath", "/api/orders/{orderId}/pay",
                    "errorCode", "PAY_401"
                )
            )
        );
        assertThat(candidateResult.status()).isIn(MemoryCandidateProcessingStatus.ACCEPTED, MemoryCandidateProcessingStatus.MERGED);
        assertThat(candidateResult.memoryId()).isNotBlank();
        var candidate = candidates.findById(candidateResult.candidateId()).orElseThrow();
        assertThat(candidate.getStatus()).isEqualTo(candidateResult.status());
        assertThat(candidate.getAuditSummary()).containsEntry("writesLongTermMemory", true);

        entityManager.flush();
        entityManager.clear();

        var memory = longTermMemories.findById(candidateResult.memoryId()).orElseThrow();
        var beforeFeedbackConfidence = memory.getConfidence();
        var beforeFeedbackSuccess = memory.getSuccessContribution();

        var secondTask = tasks.save(newTask("phase7-loop-second", apiSpec.getApiSpecId()));
        var secondBundle = unifiedContextBuilder.build(new UnifiedContextQuery(
            secondTask.getTaskId(),
            null,
            apiSpec.getApiSpecId(),
            null,
            "failure_analysis",
            "payment auth failed with PAY_401 and tenant bootstrap symptoms",
            null,
            null,
            null,
            "PAY_401",
            List.of("payment", "auth", "tenant", "PAY_401"),
            400,
            MemoryUsageConsumer.FAILURE_ANALYSIS,
            "failure-analysis:phase7-loop-second"
        ));

        assertThat(secondBundle.longTermMemoryContext().hits())
            .extracting(hit -> hit.memoryId())
            .contains(memory.getMemoryId());
        assertThat(secondBundle.citations())
            .anySatisfy(citation -> {
                assertThat(citation.citationType()).isEqualTo("long_term_memory");
                assertThat(citation.sourceId()).isEqualTo(memory.getMemoryId());
            });
        var positiveUsage = usageFor(secondTask.getTaskId(), memory.getMemoryId());
        assertThat(positiveUsage.getConsumer()).isEqualTo(MemoryUsageConsumer.FAILURE_ANALYSIS);

        var positive = usefulnessFeedback.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            positiveUsage.getUsageId(),
            "failure-analysis",
            MemoryUsefulnessOutcome.POSITIVE,
            "next task succeeded after using the recalled tenant bootstrap memory",
            Map.of("downstreamOutcome", "task-succeeded")
        ));

        assertThat(positive.status()).isEqualTo(MemoryUsefulnessFeedbackStatus.RECORDED);
        var afterPositive = longTermMemories.findById(memory.getMemoryId()).orElseThrow();
        assertThat(afterPositive.getConfidence()).isGreaterThan(beforeFeedbackConfidence);
        assertThat(afterPositive.getSuccessContribution()).isGreaterThan(beforeFeedbackSuccess);
        var confidenceAfterPositive = afterPositive.getConfidence();
        var successContributionAfterPositive = afterPositive.getSuccessContribution();

        var thirdTask = tasks.save(newTask("phase7-loop-third", apiSpec.getApiSpecId()));
        unifiedContextBuilder.build(new UnifiedContextQuery(
            thirdTask.getTaskId(),
            null,
            apiSpec.getApiSpecId(),
            null,
            "failure_analysis",
            "payment PAY_401 investigation but human says tenant bootstrap was misleading",
            null,
            null,
            null,
            "PAY_401",
            List.of("payment", "auth", "tenant", "PAY_401"),
            400,
            MemoryUsageConsumer.FAILURE_ANALYSIS,
            "failure-analysis:phase7-loop-third"
        ));
        var negativeUsage = usageFor(thirdTask.getTaskId(), memory.getMemoryId());
        var negative = usefulnessFeedback.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            negativeUsage.getUsageId(),
            "human-reviewer",
            MemoryUsefulnessOutcome.NEGATIVE,
            "human review found this recalled memory misleading for the later task",
            Map.of("downstreamOutcome", "human-rejected")
        ));

        assertThat(negative.status()).isEqualTo(MemoryUsefulnessFeedbackStatus.RECORDED);
        var afterNegative = longTermMemories.findById(memory.getMemoryId()).orElseThrow();
        assertThat(afterNegative.getConfidence()).isLessThan(confidenceAfterPositive);
        assertThat(afterNegative.getSuccessContribution()).isLessThan(successContributionAfterPositive);
        assertThat(negative.feedback().getTaskId()).isEqualTo(thirdTask.getTaskId());
        assertThat(negative.feedback().getUsageId()).isEqualTo(negativeUsage.getUsageId());
    }

    private MemoryUsageRecord usageFor(String taskId, String memoryId) {
        return usageRecords.findAllByTaskIdOrderByCreatedAtAscUsageIdAsc(taskId).stream()
            .filter(record -> record.getMemoryId().equals(memoryId))
            .findFirst()
            .orElseThrow();
    }

    private Task newTask(String taskId, String apiSpecId) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Payment PAY_401 investigation " + taskId);
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("issue-08");
        task.setTargetApiSpecIds(List.of(apiSpecId));
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.PENDING);
        task.setPriority(TaskPriority.HIGH);
        task.setCreator("phase7-test");
        task.setMetadata(Map.of("goal", "prove memory feedback closed loop"));
        return task;
    }

    private ApiSpec newApiSpec() {
        var apiSpec = new ApiSpec();
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("payment");
        apiSpec.setHttpMethod(HttpMethod.POST);
        apiSpec.setPath("/api/orders/{orderId}/pay");
        apiSpec.setSummary("Pay order");
        apiSpec.setDescription("Pay an order by id.");
        apiSpec.setOperationId("payOrder");
        apiSpec.setParameters(Map.of("orderId", Map.of("type", "string")));
        apiSpec.setConstraints(Map.of("requiresIdempotencyKey", true));
        apiSpec.setAuth(Map.of("required", true));
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("orders-openapi.yaml");
        apiSpec.setSourceLocation(Map.of("line", 12));
        apiSpec.setKnowledgeContextReady(false);
        apiSpec.setPresentInLatestAnalysis(true);
        return apiSpec;
    }
}
