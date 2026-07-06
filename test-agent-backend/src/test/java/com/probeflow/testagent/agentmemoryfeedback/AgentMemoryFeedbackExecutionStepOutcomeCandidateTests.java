package com.probeflow.testagent.agentmemoryfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.ExecutorType;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.failureanalysis.FailureAnalysisApplicationService;
import com.probeflow.testagent.failureanalysis.FailureAnalysisRequest;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.observation.AnalysisLevel;
import com.probeflow.testagent.observation.ObservationRepository;
import com.probeflow.testagent.orchestration.StepOutcome;
import com.probeflow.testagent.task.PlanStepStatus;
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
class AgentMemoryFeedbackExecutionStepOutcomeCandidateTests {

    @Autowired
    private FailureAnalysisApplicationService failureAnalysis;

    @Autowired
    private AgentMemoryFeedbackApplicationService memoryFeedback;

    @Autowired
    private MemoryCandidateRecordRepository candidates;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private ExecutionRecordRepository executionRecords;

    @Autowired
    private ObservationRepository observations;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private EntityManager entityManager;

    @Test
    void severeExecutionFailureCreatesAuditedCandidateAndRepeatedAnalysisIsIdempotent() {
        var taskId = "task-phase7-execution-candidate";
        tasks.save(newTask(taskId));
        var record = executionRecords.save(newExecutionRecord(
            taskId,
            "case-phase7-503",
            OverallStatus.FAILED,
            Map.of("method", "POST", "path", "/api/payments/charge"),
            Map.of("statusCode", 503, "failureType", "SERVER_ERROR"),
            List.of()
        ));
        record.setStatusCode(503);
        entityManager.flush();
        entityManager.clear();

        var first = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(record.getExecutionId()));
        var second = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(record.getExecutionId()));

        assertThat(first.memoryCandidate().accepted()).isTrue();
        assertThat(first.memoryCandidate().created()).isTrue();
        assertThat(second.memoryCandidate().accepted()).isTrue();
        assertThat(second.memoryCandidate().created()).isFalse();
        assertThat(second.memoryCandidate().merged()).isTrue();
        assertThat(second.memoryCandidate().memoryId()).isEqualTo(first.memoryCandidate().memoryId());
        assertThat(longTermMemories.findAll()).hasSize(1);

        var candidate = candidates.findBySourceTypeAndSourceRefAndTaskId(
            AgentMemoryCandidateSourceType.EXECUTION_RECORD,
            record.getExecutionId(),
            taskId
        ).orElseThrow();
        assertThat(candidate.getStatus()).isEqualTo(MemoryCandidateProcessingStatus.ACCEPTED);
        assertThat(candidate.getMemoryId()).isEqualTo(first.memoryCandidate().memoryId());
        assertThat(candidate.getMetadata())
            .containsEntry("classification", "SERVER_ERROR")
            .containsEntry("riskLevel", "HIGH")
            .containsEntry("retryable", true)
            .containsEntry("apiPath", "/api/payments/charge")
            .containsEntry("statusCode", 503)
            .containsEntry("errorCode", "SERVER_ERROR_503");
        assertThat(candidate.getRefineryResultSummary()).containsEntry("refineryInvoked", true);
        assertThat(candidate.getAuditSummary()).containsEntry("writesLongTermMemory", true);

        entityManager.flush();
        entityManager.clear();
        var unchanged = executionRecords.findById(record.getExecutionId()).orElseThrow();
        assertThat(unchanged.getOverallStatus()).isEqualTo(OverallStatus.FAILED);
        assertThat(unchanged.getStatusCode()).isEqualTo(503);
        assertThat(unchanged.getRequestSnapshot()).containsEntry("path", "/api/payments/charge");
        assertThat(observations.findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
            record.getExecutionId(),
            AnalysisLevel.BASIC
        )).isNotEmpty();
    }

    @Test
    void lowValueFailureIsAuditedAsRejectedAndPassedExecutionDoesNotCreateMemoryCandidate() {
        var taskId = "task-phase7-low-value";
        tasks.save(newTask(taskId));
        var lowValue = executionRecords.save(newExecutionRecord(
            taskId,
            "case-phase7-409",
            OverallStatus.FAILED,
            Map.of("method", "POST", "path", "/api/orders"),
            Map.of("statusCode", 409),
            List.of(Map.of("type", "STATUS_CODE", "expected", 201, "actual", 409, "status", "FAILED", "critical", false))
        ));
        lowValue.setCriticalFailed(false);
        lowValue.setStatusCode(409);
        var passed = executionRecords.save(newExecutionRecord(
            taskId,
            "case-phase7-pass",
            OverallStatus.PASSED,
            Map.of("method", "GET", "path", "/api/orders"),
            Map.of("statusCode", 200),
            List.of()
        ));
        passed.setStatusCode(200);
        entityManager.flush();
        entityManager.clear();

        var rejected = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(lowValue.getExecutionId()));
        var skipped = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(passed.getExecutionId()));

        assertThat(rejected.memoryCandidate().attempted()).isTrue();
        assertThat(rejected.memoryCandidate().accepted()).isFalse();
        assertThat(rejected.memoryCandidate().rejectionReason()).isEqualTo("low-confidence");
        assertThat(skipped.memoryCandidate().attempted()).isFalse();
        assertThat(longTermMemories.findAll()).isEmpty();

        var rejectedCandidate = candidates.findBySourceTypeAndSourceRefAndTaskId(
            AgentMemoryCandidateSourceType.EXECUTION_RECORD,
            lowValue.getExecutionId(),
            taskId
        ).orElseThrow();
        assertThat(rejectedCandidate.getStatus()).isEqualTo(MemoryCandidateProcessingStatus.REJECTED);
        assertThat(rejectedCandidate.getRejectionReason()).isEqualTo("low-confidence");
        assertThat(rejectedCandidate.getMetadata())
            .containsEntry("classification", "STATUS_MISMATCH")
            .containsEntry("riskLevel", "LOW")
            .containsEntry("retryable", false)
            .containsEntry("statusCode", 409)
            .containsEntry("errorCode", "STATUS_MISMATCH_409");
        assertThat(candidates.findBySourceTypeAndSourceRefAndTaskId(
            AgentMemoryCandidateSourceType.EXECUTION_RECORD,
            passed.getExecutionId(),
            taskId
        )).isEmpty();
    }

    @Test
    void failedStepOutcomeCreatesAuditedMemoryCandidate() {
        var taskId = "task-phase7-step-outcome";
        tasks.save(newTask(taskId));
        var outcome = new StepOutcome(
            PlanStepStatus.FAILED,
            TaskStatus.FAILED,
            "Execution timed out while charging a payment.",
            List.of("execution:exec-phase7-step"),
            "execution:exec-phase7-step",
            List.of("timeout after 30s", "statusCode=503"),
            true
        );

        var result = memoryFeedback.refineStepOutcomeCandidate(taskId, "step-phase7-timeout", outcome);

        assertThat(result.status()).isEqualTo(MemoryCandidateProcessingStatus.ACCEPTED);
        assertThat(result.memoryId()).isNotBlank();
        var candidate = candidates.findBySourceTypeAndSourceRefAndTaskId(
            AgentMemoryCandidateSourceType.STEP_OUTCOME,
            "step-outcome:step-phase7-timeout",
            taskId
        ).orElseThrow();
        assertThat(candidate.getStatus()).isEqualTo(MemoryCandidateProcessingStatus.ACCEPTED);
        assertThat(candidate.getTags()).contains("step-outcome", "failure-analysis", "timeout", "high-risk", "retryable");
        assertThat(candidate.getMetadata())
            .containsEntry("classification", "TIMEOUT")
            .containsEntry("riskLevel", "HIGH")
            .containsEntry("retryable", true)
            .containsEntry("stepId", "step-phase7-timeout")
            .containsEntry("stepStatus", "FAILED")
            .containsEntry("taskStatus", "FAILED")
            .containsEntry("errorCode", "TIMEOUT");

        var memory = longTermMemories.findById(result.memoryId()).orElseThrow();
        assertThat(memory.getSourceRef()).isEqualTo("step-outcome:step-phase7-timeout");
        assertThat(memory.getMetadata()).containsEntry("classification", "TIMEOUT");
    }

    private Task newTask(String taskId) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Phase 7 memory feedback " + taskId);
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("manual");
        task.setTargetApiSpecIds(List.of());
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase7-test");
        task.setMetadata(Map.of());
        return task;
    }

    private ExecutionRecord newExecutionRecord(
        String taskId,
        String caseId,
        OverallStatus status,
        Map<String, Object> requestSnapshot,
        Map<String, Object> responseSnapshot,
        List<Map<String, Object>> assertionResults
    ) {
        var record = new ExecutionRecord();
        record.setTaskId(taskId);
        record.setCaseId(caseId);
        record.setExecutorType(ExecutorType.HTTP);
        record.setEnvironment("test");
        record.setRequestSnapshot(requestSnapshot);
        record.setResponseSnapshot(responseSnapshot);
        record.setAssertionResults(assertionResults);
        record.setOverallStatus(status);
        record.setCriticalFailed(status == OverallStatus.FAILED);
        record.setDurationMs(123L);
        record.setStatusCode(responseSnapshot.get("statusCode") instanceof Number number ? number.intValue() : null);
        record.setErrorMessage(status == OverallStatus.ERROR ? "Connection refused" : null);
        return record;
    }
}
