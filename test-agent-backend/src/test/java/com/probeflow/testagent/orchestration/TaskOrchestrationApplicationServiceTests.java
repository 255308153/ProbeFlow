package com.probeflow.testagent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.probeflow.testagent.testcasedraft.PromotionMode;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaskOrchestrationApplicationServiceTests {

    @Autowired
    private TaskInitializationService taskInitializationService;

    @Autowired
    private TaskOrchestrationApplicationService taskOrchestrationApplicationService;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private RecordingPlanStepRunner planStepRunner;

    @BeforeEach
    void clearRunner() {
        planStepRunner.clear();
    }

    @Test
    void runsPendingPlanStepsInOrderAndCompletesTask() {
        var initialized = taskInitializationService.initialize(apiTestRequest());

        var result = taskOrchestrationApplicationService.runInitializedTask(initialized.taskId());

        entityManager.flush();
        entityManager.clear();

        assertThat(result.taskId()).isEqualTo(initialized.taskId());
        assertThat(result.finalStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.completedStepCount()).isEqualTo(6);
        assertThat(result.blockerDetails()).isEmpty();
        assertThat(tasks.findById(initialized.taskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(planStepRunner.calls()).containsExactly(
            PlanStepType.ANALYZE_CODE_API,
            PlanStepType.RETRIEVE_KNOWLEDGE,
            PlanStepType.GENERATE_CASES,
            PlanStepType.EXECUTE_BATCH,
            PlanStepType.ANALYZE_FAILURE,
            PlanStepType.GENERATE_REPORT
        );
        assertThat(planStepRunner.observedTaskStatuses())
            .containsEntry(PlanStepType.ANALYZE_CODE_API, TaskStatus.ANALYZING)
            .containsEntry(PlanStepType.RETRIEVE_KNOWLEDGE, TaskStatus.ANALYZING)
            .containsEntry(PlanStepType.EXECUTE_BATCH, TaskStatus.EXECUTING)
            .containsEntry(PlanStepType.ANALYZE_FAILURE, TaskStatus.ANALYZING_RESULTS)
            .containsEntry(PlanStepType.GENERATE_REPORT, TaskStatus.ANALYZING_RESULTS);
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(initialized.taskId()))
            .allSatisfy(step -> {
                assertThat(step.getStepStatus()).isEqualTo(PlanStepStatus.SUCCESS);
                assertThat(step.getStartedAt()).isNotNull();
                assertThat(step.getFinishedAt()).isNotNull();
            });
    }

    @Test
    void resumeSkipsCompletedStepsAndContinuesFromPendingStep() {
        var initialized = taskInitializationService.initialize(apiTestRequest());
        var firstTwoSteps = planSteps.findByTaskIdOrderByStepOrderAsc(initialized.taskId()).subList(0, 2);
        firstTwoSteps.forEach(this::markSucceeded);

        var result = taskOrchestrationApplicationService.runInitializedTask(initialized.taskId());

        assertThat(result.finalStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.completedStepCount()).isEqualTo(6);
        assertThat(planStepRunner.calls()).containsExactly(
            PlanStepType.GENERATE_CASES,
            PlanStepType.EXECUTE_BATCH,
            PlanStepType.ANALYZE_FAILURE,
            PlanStepType.GENERATE_REPORT
        );
    }

    @Test
    void runningStepIsResumedWithoutRerunningCompletedSteps() {
        var initialized = taskInitializationService.initialize(regressionRequest());
        var steps = planSteps.findByTaskIdOrderByStepOrderAsc(initialized.taskId());
        markSucceeded(steps.get(0));
        steps.get(1).setStepStatus(PlanStepStatus.RUNNING);
        steps.get(1).setStartedAt(Instant.parse("2026-01-01T00:00:00Z"));
        planSteps.save(steps.get(1));

        var result = taskOrchestrationApplicationService.runInitializedTask(initialized.taskId());

        assertThat(result.finalStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(planStepRunner.calls()).containsExactly(
            PlanStepType.ANALYZE_FAILURE,
            PlanStepType.GENERATE_REPORT
        );
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(initialized.taskId()))
            .allSatisfy(step -> assertThat(step.getStepStatus()).isEqualTo(PlanStepStatus.SUCCESS));
    }

    @Test
    void cancelledTaskDoesNotExecuteAdditionalSteps() {
        var initialized = taskInitializationService.initialize(regressionRequest());
        var task = tasks.findById(initialized.taskId()).orElseThrow();
        task.setStatus(TaskStatus.CANCELLED);
        tasks.save(task);

        var result = taskOrchestrationApplicationService.runInitializedTask(initialized.taskId());

        assertThat(result.finalStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(result.completedStepCount()).isZero();
        assertThat(result.blockerDetails()).containsExactly("Task is cancelled");
        assertThat(planStepRunner.calls()).isEmpty();
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(initialized.taskId()))
            .allSatisfy(step -> assertThat(step.getStepStatus()).isEqualTo(PlanStepStatus.PENDING));
    }

    @Test
    void failedCriticalStepStopsAndSkipsDownstreamSteps() {
        var initialized = taskInitializationService.initialize(apiTestRequest());
        planStepRunner.failOn(PlanStepType.GENERATE_CASES, "case generation failed");

        var result = taskOrchestrationApplicationService.runInitializedTask(initialized.taskId());

        assertThat(result.finalStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.completedStepCount()).isEqualTo(2);
        assertThat(result.blockerDetails()).containsExactly("case generation failed");
        assertThat(tasks.findById(initialized.taskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(planStepRunner.calls()).containsExactly(
            PlanStepType.ANALYZE_CODE_API,
            PlanStepType.RETRIEVE_KNOWLEDGE,
            PlanStepType.GENERATE_CASES
        );
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(initialized.taskId()))
            .extracting(PlanStep::getStepStatus)
            .containsExactly(
                PlanStepStatus.SUCCESS,
                PlanStepStatus.SUCCESS,
                PlanStepStatus.FAILED,
                PlanStepStatus.SKIPPED,
                PlanStepStatus.SKIPPED,
                PlanStepStatus.SKIPPED
            );
    }

    private TaskInitializationRequest apiTestRequest() {
        return new TaskInitializationRequest(
            null,
            TaskType.API_TEST,
            "Explore order APIs",
            TaskSourceType.OPENAPI,
            "source-material-orders",
            PromotionMode.AUTO,
            List.of("api-create-order", "api-pay-order"),
            List.of(),
            TaskPriority.HIGH,
            "phase9-test",
            Map.of()
        );
    }

    private TaskInitializationRequest regressionRequest() {
        return new TaskInitializationRequest(
            null,
            TaskType.REGRESSION,
            "Run regression",
            TaskSourceType.MANUAL,
            "regression-suite",
            PromotionMode.AUTO,
            List.of("api-create-order"),
            List.of("case-happy-path"),
            TaskPriority.MEDIUM,
            "phase9-test",
            Map.of()
        );
    }

    private void markSucceeded(PlanStep step) {
        step.setStepStatus(PlanStepStatus.SUCCESS);
        step.setStartedAt(Instant.parse("2026-01-01T00:00:00Z"));
        step.setFinishedAt(Instant.parse("2026-01-01T00:00:01Z"));
        planSteps.save(step);
    }

    @TestConfiguration
    static class OrchestrationTestConfiguration {

        @Bean
        @Primary
        RecordingPlanStepRunner recordingPlanStepRunner(TaskRepository tasks) {
            return new RecordingPlanStepRunner(tasks);
        }
    }

    static class RecordingPlanStepRunner implements PlanStepRunner {

        private final TaskRepository tasks;
        private final List<PlanStepType> calls = new ArrayList<>();
        private final Map<PlanStepType, TaskStatus> observedTaskStatuses = new LinkedHashMap<>();
        private final Map<PlanStepType, StepOutcome> outcomes = new EnumMap<>(PlanStepType.class);

        RecordingPlanStepRunner(TaskRepository tasks) {
            this.tasks = tasks;
        }

        @Override
        public StepOutcome run(Task task, PlanStep step) {
            calls.add(step.getStepType());
            observedTaskStatuses.put(step.getStepType(), tasks.findById(task.getTaskId()).orElseThrow().getStatus());
            return outcomes.getOrDefault(step.getStepType(), StepOutcome.succeeded());
        }

        void failOn(PlanStepType stepType, String blocker) {
            outcomes.put(stepType, StepOutcome.failed(blocker));
        }

        void clear() {
            calls.clear();
            observedTaskStatuses.clear();
            outcomes.clear();
        }

        List<PlanStepType> calls() {
            return List.copyOf(calls);
        }

        Map<PlanStepType, TaskStatus> observedTaskStatuses() {
            return Map.copyOf(observedTaskStatuses);
        }
    }
}
