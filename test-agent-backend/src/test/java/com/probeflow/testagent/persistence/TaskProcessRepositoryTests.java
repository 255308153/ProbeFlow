package com.probeflow.testagent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.report.Report;
import com.probeflow.testagent.report.ReportRepository;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.PlanStep;
import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.PlanStepType;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecution;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionRepository;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionStatus;
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
class TaskProcessRepositoryTests {

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private TaskCaseExecutionRepository taskCaseExecutions;

    @Autowired
    private ReportRepository reports;

    @Autowired
    private EntityManager entityManager;

    @Test
    void taskCanStoreProcessStateTargetsPromotionModeAndMemoryTracking() {
        var task = new Task();
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Generate order API cases");
        task.setStatus(TaskStatus.WAITING_FOR_REVIEW);
        task.setSourceType(TaskSourceType.CODE_REPO);
        task.setSourceRef("git://order-service");
        task.setTargetApiSpecIds(List.of("api-spec-create-order", "api-spec-pay-order"));
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.PENDING);
        task.setPriority(TaskPriority.HIGH);
        task.setCreator("tester");
        task.setMetadata(Map.of("branch", "main", "environment", "test"));

        var saved = tasks.save(task);
        entityManager.flush();
        entityManager.clear();

        var loaded = tasks.findById(saved.getTaskId()).orElseThrow();

        assertThat(loaded.getTaskType()).isEqualTo(TaskType.API_TEST);
        assertThat(loaded.getStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
        assertThat(loaded.getTargetApiSpecIds()).containsExactly("api-spec-create-order", "api-spec-pay-order");
        assertThat(loaded.getPromotionMode()).isEqualTo(PromotionMode.MANUAL);
        assertThat(loaded.getMemoryRefinementStatus()).isEqualTo(MemoryRefinementStatus.PENDING);
        assertThat(loaded.getMetadata()).containsEntry("branch", "main");
        assertThat(loaded.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(loaded.getUpdatedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void planStepTaskCaseExecutionAndReportCanBePersistedForATask() {
        var task = new Task();
        task.setTaskType(TaskType.REGRESSION);
        task.setTaskName("Run order regression");
        task.setStatus(TaskStatus.EXECUTING);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setTargetApiSpecIds(List.of("api-spec-create-order"));
        task.setPromotionMode(PromotionMode.AUTO);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.NOT_REQUIRED);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("tester");
        task.setMetadata(Map.of("suite", "order-regression"));
        var savedTask = tasks.save(task);

        var planStep = new PlanStep();
        planStep.setTaskId(savedTask.getTaskId());
        planStep.setStepType(PlanStepType.EXECUTE_BATCH);
        planStep.setStepStatus(PlanStepStatus.RUNNING);
        planStep.setStepOrder(1);
        planStep.setGoal("Execute selected formal cases");
        planStep.setInputRef("task-case-executions");
        planStep.setRetryCount(1);
        planStep.setStartedAt(Instant.parse("2026-07-02T12:00:00Z"));
        planSteps.save(planStep);

        var taskCaseExecution = new TaskCaseExecution();
        taskCaseExecution.setTaskId(savedTask.getTaskId());
        taskCaseExecution.setCaseId("case-create-order-happy-path");
        taskCaseExecution.setExecutionMode(ExecutionMode.BATCH);
        taskCaseExecution.setSnapshotJson(Map.of(
            "title", "create-order-happy-path",
            "detail", Map.of("method", "POST", "path", "/api/orders")
        ));
        taskCaseExecution.setExecutionStatus(TaskCaseExecutionStatus.EXECUTING);
        taskCaseExecution.setExecutionRecordId("execution-record-pending");
        taskCaseExecutions.save(taskCaseExecution);

        var report = new Report();
        report.setTaskId(savedTask.getTaskId());
        report.setSummary("Regression still running");
        report.setCaseCount(1);
        report.setPassCount(0);
        report.setFailCount(0);
        report.setWarningCount(0);
        report.setRiskSummary("No risks observed yet");
        report.setFindings(List.of(Map.of("type", "placeholder", "message", "execution in progress")));
        report.setSuggestions(List.of(Map.of("type", "follow_up", "message", "wait for completion")));
        reports.save(report);

        entityManager.flush();
        entityManager.clear();

        assertThat(planSteps.findAll()).singleElement().satisfies(loaded -> {
            assertThat(loaded.getTaskId()).isEqualTo(savedTask.getTaskId());
            assertThat(loaded.getStepType()).isEqualTo(PlanStepType.EXECUTE_BATCH);
            assertThat(loaded.getStepStatus()).isEqualTo(PlanStepStatus.RUNNING);
            assertThat(loaded.getRetryCount()).isEqualTo(1);
        });
        assertThat(taskCaseExecutions.findAll()).singleElement().satisfies(loaded -> {
            assertThat(loaded.getTaskId()).isEqualTo(savedTask.getTaskId());
            assertThat(loaded.getExecutionMode()).isEqualTo(ExecutionMode.BATCH);
            assertThat(loaded.getSnapshotJson()).containsEntry("title", "create-order-happy-path");
        });
        assertThat(reports.findAll()).singleElement().satisfies(loaded -> {
            assertThat(loaded.getTaskId()).isEqualTo(savedTask.getTaskId());
            assertThat(loaded.getCaseCount()).isEqualTo(1);
            assertThat(loaded.getFindings()).hasSize(1);
            assertThat(loaded.getSuggestions()).hasSize(1);
        });
    }
}
