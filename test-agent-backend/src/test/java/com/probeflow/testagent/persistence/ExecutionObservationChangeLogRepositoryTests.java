package com.probeflow.testagent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.changelog.ChangeEntityType;
import com.probeflow.testagent.changelog.ChangeLog;
import com.probeflow.testagent.changelog.ChangeLogRepository;
import com.probeflow.testagent.changelog.ChangeType;
import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.ExecutorType;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.observation.AnalysisLevel;
import com.probeflow.testagent.observation.Observation;
import com.probeflow.testagent.observation.ObservationRepository;
import com.probeflow.testagent.observation.ObservationRiskLevel;
import com.probeflow.testagent.observation.ObservationSource;
import com.probeflow.testagent.observation.ObservationType;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
class ExecutionObservationChangeLogRepositoryTests {

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private ExecutionRecordRepository executionRecords;

    @Autowired
    private ObservationRepository observations;

    @Autowired
    private ChangeLogRepository changeLogs;

    @Autowired
    private EntityManager entityManager;

    @Test
    void executionRecordStoresRawHttpFactsAndJsonSnapshots() {
        var task = tasks.save(newTask());

        var record = new ExecutionRecord();
        record.setTaskId(task.getTaskId());
        record.setCaseId("case-create-order-happy-path");
        record.setStepId("plan-step-execute-single");
        record.setExecutorType(ExecutorType.HTTP);
        record.setEnvironment("test");
        record.setRequestSnapshot(Map.of(
            "method", "POST",
            "path", "/api/orders",
            "headers", Map.of("Content-Type", "application/json"),
            "body", Map.of("sku", "A-100", "quantity", 2)
        ));
        record.setResponseSnapshot(Map.of(
            "statusCode", 201,
            "body", Map.of("orderId", "order-123", "status", "CREATED")
        ));
        record.setAssertionResults(List.of(
            Map.of("assertionId", "status-code", "passed", true),
            Map.of("assertionId", "json-order-id", "passed", true)
        ));
        record.setOverallStatus(OverallStatus.PASSED);
        record.setCriticalFailed(false);
        record.setDurationMs(187L);
        record.setStatusCode(201);

        var saved = executionRecords.save(record);
        entityManager.flush();
        entityManager.clear();

        var loaded = executionRecords.findById(saved.getExecutionId()).orElseThrow();

        assertThat(loaded.getTaskId()).isEqualTo(task.getTaskId());
        assertThat(loaded.getCaseId()).isEqualTo("case-create-order-happy-path");
        assertThat(loaded.getExecutorType()).isEqualTo(ExecutorType.HTTP);
        assertThat(loaded.getEnvironment()).isEqualTo("test");
        assertThat(loaded.getRequestSnapshot()).containsEntry("method", "POST");
        assertThat(loaded.getResponseSnapshot()).containsEntry("statusCode", 201);
        assertThat(loaded.getAssertionResults()).hasSize(2);
        assertThat(loaded.getOverallStatus()).isEqualTo(OverallStatus.PASSED);
        assertThat(loaded.isCriticalFailed()).isFalse();
        assertThat(loaded.getDurationMs()).isEqualTo(187L);
        assertThat(loaded.getStatusCode()).isEqualTo(201);
        assertThat(loaded.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void observationStoresStructuredAnalysisSeparateFromExecutionFacts() {
        var task = tasks.save(newTask());
        var executionRecord = new ExecutionRecord();
        executionRecord.setTaskId(task.getTaskId());
        executionRecord.setCaseId("case-create-order-validation");
        executionRecord.setExecutorType(ExecutorType.HTTP);
        executionRecord.setEnvironment("test");
        executionRecord.setRequestSnapshot(Map.of("path", "/api/orders"));
        executionRecord.setResponseSnapshot(Map.of("statusCode", 400));
        executionRecord.setAssertionResults(List.of(Map.of("assertionId", "error-code", "passed", false)));
        executionRecord.setOverallStatus(OverallStatus.FAILED);
        executionRecord.setCriticalFailed(true);
        executionRecord.setDurationMs(94L);
        executionRecord.setStatusCode(400);
        var savedRecord = executionRecords.save(executionRecord);

        var observation = new Observation();
        observation.setTaskId(task.getTaskId());
        observation.setExecutionId(savedRecord.getExecutionId());
        observation.setObservationType(ObservationType.ASSERTION_FAILURE_ANALYSIS);
        observation.setAnalysisLevel(AnalysisLevel.DEEP);
        observation.setSummary("Validation error payload does not match the API contract.");
        observation.setFailureReason("Missing machine-readable error code.");
        observation.setRiskLevel(ObservationRiskLevel.HIGH);
        observation.setNextSuggestion("Add an errorCode field to the validation failure response.");
        observation.setSource(ObservationSource.AI);

        var saved = observations.save(observation);
        entityManager.flush();
        entityManager.clear();

        var loaded = observations.findById(saved.getObservationId()).orElseThrow();

        assertThat(loaded.getTaskId()).isEqualTo(task.getTaskId());
        assertThat(loaded.getExecutionId()).isEqualTo(savedRecord.getExecutionId());
        assertThat(loaded.getObservationType()).isEqualTo(ObservationType.ASSERTION_FAILURE_ANALYSIS);
        assertThat(loaded.getAnalysisLevel()).isEqualTo(AnalysisLevel.DEEP);
        assertThat(loaded.getSummary()).contains("API contract");
        assertThat(loaded.getFailureReason()).contains("error code");
        assertThat(loaded.getRiskLevel()).isEqualTo(ObservationRiskLevel.HIGH);
        assertThat(loaded.getSource()).isEqualTo(ObservationSource.AI);
    }

    @Test
    void changeLogStoresEntityIdentityAndBeforeAfterSnapshots() {
        var task = tasks.save(newTask());

        var changeLog = new ChangeLog();
        changeLog.setEntityType(ChangeEntityType.TEST_CASE);
        changeLog.setEntityId("case-create-order-happy-path");
        changeLog.setBeforeSnapshot(Map.of(
            "title", "create order - happy path",
            "manualEdited", false,
            "detail", Map.of("expectedStatusCode", 200)
        ));
        changeLog.setAfterSnapshot(Map.of(
            "title", "create order - happy path",
            "manualEdited", true,
            "detail", Map.of("expectedStatusCode", 201)
        ));
        changeLog.setChangeType(ChangeType.MANUAL_EDIT);
        changeLog.setChangedBy("tester");
        changeLog.setTaskId(task.getTaskId());

        var saved = changeLogs.save(changeLog);
        entityManager.flush();
        entityManager.clear();

        var loaded = changeLogs.findById(saved.getChangeId()).orElseThrow();

        assertThat(loaded.getEntityType()).isEqualTo(ChangeEntityType.TEST_CASE);
        assertThat(loaded.getEntityId()).isEqualTo("case-create-order-happy-path");
        assertThat(loaded.getBeforeSnapshot()).containsEntry("manualEdited", false);
        assertThat(loaded.getAfterSnapshot()).containsEntry("manualEdited", true);
        assertThat(loaded.getChangeType()).isEqualTo(ChangeType.MANUAL_EDIT);
        assertThat(loaded.getChangedBy()).isEqualTo("tester");
        assertThat(loaded.getTaskId()).isEqualTo(task.getTaskId());
        assertThat(loaded.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
    }

    private Task newTask() {
        var task = new Task();
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Execute order API cases");
        task.setStatus(TaskStatus.EXECUTING);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setTargetApiSpecIds(List.of("api-spec-create-order"));
        task.setPromotionMode(PromotionMode.AUTO);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.NOT_REQUIRED);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("tester");
        task.setMetadata(Map.of("environment", "test"));
        return task;
    }
}
