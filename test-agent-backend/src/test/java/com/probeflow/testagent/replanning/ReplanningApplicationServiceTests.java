package com.probeflow.testagent.replanning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.probeflow.testagent.controlledplanner.FakeControlledPlanner;
import com.probeflow.testagent.controlledplanner.FakePlannerScenario;
import com.probeflow.testagent.controlledplanner.PlannerConstraint;
import com.probeflow.testagent.orchestration.StepOutcome;
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
import java.nio.file.Files;
import java.nio.file.Path;
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
class ReplanningApplicationServiceTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Autowired
    private ReplanningApplicationService replanning;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private EntityManager entityManager;

    @Test
    void exposesExplicitTriggerWhitelistAndRejectsArbitraryTriggerNames() {
        assertThat(ReplanningTrigger.values()).containsExactly(
            ReplanningTrigger.PLAN_STEP_FAILED,
            ReplanningTrigger.EXECUTION_READINESS_MISSING,
            ReplanningTrigger.FAILURE_ANALYSIS_HIGH_RISK,
            ReplanningTrigger.REVIEW_COMPLETED,
            ReplanningTrigger.CONTEXT_MISSING,
            ReplanningTrigger.HUMAN_INPUT_REQUIRED
        );

        assertThatThrownBy(() -> ReplanningTrigger.valueOf("AUTO_GPT_LOOP"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resultContractCoversAllRequiredStatusesAndAuditFields() {
        assertThat(ReplanningStatus.values()).contains(
            ReplanningStatus.APPLIED,
            ReplanningStatus.NOOP,
            ReplanningStatus.WAITING_FOR_HUMAN,
            ReplanningStatus.REJECTED_BY_POLICY,
            ReplanningStatus.NOT_TRIGGERABLE,
            ReplanningStatus.FAILED
        );

        var result = ReplanningResult.noop(ReplanningTrigger.CONTEXT_MISSING, "Nothing changed");

        assertThat(result.status()).isEqualTo(ReplanningStatus.NOOP);
        assertThat(result.trigger()).isEqualTo(ReplanningTrigger.CONTEXT_MISSING);
        assertThat(result.blockers()).isEmpty();
        assertThat(result.decisionSummary()).containsEntry("plannerCalled", false);
        assertThat(result.policySummary()).containsEntry("policyValidated", false);
        assertThat(result.planMutationSummary()).containsEntry("mutationApplied", false);
        assertThat(result.insertedStepIds()).isEmpty();
        assertThat(result.skippedStepIds()).isEmpty();
    }

    @Test
    void requestRequiresTaskIdAndTriggerButAllowsOptionalRecoveryContext() {
        assertThatThrownBy(() -> ReplanningRequest.of(null, ReplanningTrigger.PLAN_STEP_FAILED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Task id is required");
        assertThatThrownBy(() -> ReplanningRequest.of("task-1", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Replanning trigger is required");

        var outcome = StepOutcome.failed("Generation failed");
        var request = new ReplanningRequest(
            " task-1 ",
            ReplanningTrigger.PLAN_STEP_FAILED,
            " step-1 ",
            outcome,
            Map.of("reviewNote", "approved"),
            true
        );

        assertThat(request.taskId()).isEqualTo("task-1");
        assertThat(request.sourceStepId()).isEqualTo("step-1");
        assertThat(request.stepOutcome()).isEqualTo(outcome);
        assertThat(request.humanInput()).containsEntry("reviewNote", "approved");
        assertThat(request.reviewCompleted()).isTrue();
    }

    @Test
    void nullRequestHasExplicitFailureBehavior() {
        assertThatThrownBy(() -> replanning.replan(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Replanning request is required");
    }

    @Test
    void completedAndCancelledTasksAreNotTriggerable() {
        var completed = saveTask("task-replan-completed", TaskStatus.COMPLETED);
        var cancelled = saveTask("task-replan-cancelled", TaskStatus.CANCELLED);

        var completedResult = replanning.replan(ReplanningRequest.of(
            completed.getTaskId(),
            ReplanningTrigger.PLAN_STEP_FAILED
        ));
        var cancelledResult = replanning.replan(ReplanningRequest.of(
            cancelled.getTaskId(),
            ReplanningTrigger.EXECUTION_READINESS_MISSING
        ));

        assertThat(completedResult.status()).isEqualTo(ReplanningStatus.NOT_TRIGGERABLE);
        assertThat(completedResult.blockers()).containsExactly("Completed task cannot be replanned");
        assertThat(cancelledResult.status()).isEqualTo(ReplanningStatus.NOT_TRIGGERABLE);
        assertThat(cancelledResult.blockers()).containsExactly("Cancelled task cannot be replanned");
    }

    @Test
    void minimalLegalRequestReturnsAuditableNoopAndDoesNotMutateTaskOrPlanStep() {
        var task = saveTask("task-replan-noop", TaskStatus.ANALYZING);
        var step = saveStep(task.getTaskId(), "step-replan-noop", PlanStepStatus.PENDING);

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.CONTEXT_MISSING,
            step.getStepId(),
            StepOutcome.blocked("Missing context", List.of("No auth examples found")),
            Map.of(),
            false,
            null,
            null,
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.CONTINUE.name()))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ReplanningStatus.NOOP);
        assertThat(result.trigger()).isEqualTo(ReplanningTrigger.CONTEXT_MISSING);
        assertThat(result.decisionSummary())
            .containsEntry("action", "CONTINUE")
            .containsEntry("plannerCalled", true);
        assertThat(result.policySummary()).containsEntry("validationStatus", "ALLOWED");
        assertThat(result.planMutationSummary()).containsEntry("mutationApplied", false);
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.ANALYZING);
        assertThat(planSteps.findById(step.getStepId()).orElseThrow().getStepStatus()).isEqualTo(PlanStepStatus.PENDING);
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(task.getTaskId())).hasSize(1);
    }

    @Test
    void replanningEntrypointDoesNotExecuteToolsOrUseFreeAgentRuntimeCollaborators() throws Exception {
        var source = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/replanning/ReplanningApplicationService.java"
        ));

        assertThat(source)
            .doesNotContain("ToolRouter")
            .doesNotContain("PlanStepRunner")
            .doesNotContain("LlmProvider")
            .doesNotContain("AutoGPT")
            .doesNotContain(".delete(");
    }

    private Task saveTask(String taskId, TaskStatus status) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Replanning " + taskId);
        task.setStatus(status);
        task.setSourceType(TaskSourceType.OPENAPI);
        task.setSourceRef("source-" + taskId);
        task.setTargetApiSpecIds(List.of("api-" + taskId));
        task.setPromotionMode(PromotionMode.AUTO);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase5");
        task.setMetadata(Map.of());
        return tasks.save(task);
    }

    private PlanStep saveStep(String taskId, String stepId, PlanStepStatus status) {
        var step = new PlanStep();
        step.setStepId(stepId);
        step.setTaskId(taskId);
        step.setStepType(PlanStepType.GENERATE_CASES);
        step.setStepStatus(status);
        step.setStepOrder(10);
        step.setGoal("Generate cases");
        step.setInputRef("api-" + taskId);
        return planSteps.save(step);
    }
}
