package com.probeflow.testagent.replanning;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.AgentWorkflowMode;
import com.probeflow.testagent.controlledplanner.ContextBundleSummary;
import com.probeflow.testagent.controlledplanner.FakeControlledPlanner;
import com.probeflow.testagent.controlledplanner.FakePlannerScenario;
import com.probeflow.testagent.controlledplanner.PlannerAction;
import com.probeflow.testagent.controlledplanner.PlannerConstraint;
import com.probeflow.testagent.orchestration.StepOutcome;
import com.probeflow.testagent.policyvalidator.PolicyValidationStatus;
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
class ReplanningTruncateAndReplanRecoveryTests {

    @Autowired
    private ReplanningApplicationService replanning;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private EntityManager entityManager;

    @Test
    void replanDecisionSkipsOnlyPendingDownstreamStepsAndAppendsRecoveryStep() {
        var task = saveTask("task-replan-truncate-append", TaskStatus.ANALYZING_RESULTS);
        var successBefore = saveStep(task.getTaskId(), "step-truncate-success-before", PlanStepType.RETRIEVE_KNOWLEDGE, PlanStepStatus.SUCCESS, 10, "Context ready", "context-ref");
        var failedSource = saveStep(task.getTaskId(), "step-truncate-failed-source", PlanStepType.EXECUTE_BATCH, PlanStepStatus.FAILED, 20, "Execute batch", "execution-input");
        var pendingDownstream = saveStep(task.getTaskId(), "step-truncate-pending-downstream", PlanStepType.GENERATE_REPORT, PlanStepStatus.PENDING, 30, "Generate report", "report-input");
        var runningDownstream = saveStep(task.getTaskId(), "step-truncate-running-downstream", PlanStepType.EXECUTE_SINGLE, PlanStepStatus.RUNNING, 40, "Execute single", "single-input");
        var secondPendingDownstream = saveStep(task.getTaskId(), "step-truncate-second-pending", PlanStepType.GENERATE_CASES, PlanStepStatus.PENDING, 50, "Generate cases", "case-input");
        var successAfter = saveStep(task.getTaskId(), "step-truncate-success-after", PlanStepType.GENERATE_REPORT, PlanStepStatus.SUCCESS, 60, "Existing report", "report-success");

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.PLAN_STEP_FAILED,
            failedSource.getStepId(),
            failedOutcome("Batch execution failed during replan", "executionRecord:exec-replan-1", "Assertion mismatch blocks downstream report"),
            Map.of(),
            false,
            failureAnalysisPolicy(),
            ContextBundleSummary.of("Failure evidence references executionRecord:exec-replan-1", List.of("exec:exec-replan-1"), 0, 0, 200),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.REPLAN.name()))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ReplanningStatus.APPLIED);
        assertThat(result.skippedStepIds())
            .containsExactly(pendingDownstream.getStepId(), secondPendingDownstream.getStepId());
        assertThat(result.insertedStepIds()).hasSize(1);
        assertThat(result.blockers()).containsExactly("Assertion mismatch blocks downstream report");
        assertThat(result.decisionSummary())
            .containsEntry("action", PlannerAction.REPLAN.name())
            .containsEntry("plannerCalled", true);
        assertThat(result.policySummary()).containsEntry("validationStatus", PolicyValidationStatus.ALLOWED.name());
        assertThat(result.planMutationSummary())
            .containsEntry("mutationApplied", true)
            .containsEntry("mutationType", PlannerAction.REPLAN.name())
            .containsEntry("safeStop", false)
            .containsEntry("sourceStepId", failedSource.getStepId())
            .containsEntry("trigger", ReplanningTrigger.PLAN_STEP_FAILED.name())
            .containsEntry("insertedStepType", PlanStepType.ANALYZE_FAILURE.name());

        assertStepUnchanged(successBefore.getStepId(), PlanStepStatus.SUCCESS, 10, "Context ready", "context-ref");
        assertStepUnchanged(failedSource.getStepId(), PlanStepStatus.FAILED, 20, "Execute batch", "execution-input");
        assertStepUnchanged(pendingDownstream.getStepId(), PlanStepStatus.SKIPPED, 30, "Generate report", "report-input");
        assertStepUnchanged(runningDownstream.getStepId(), PlanStepStatus.RUNNING, 40, "Execute single", "single-input");
        assertStepUnchanged(secondPendingDownstream.getStepId(), PlanStepStatus.SKIPPED, 50, "Generate cases", "case-input");
        assertStepUnchanged(successAfter.getStepId(), PlanStepStatus.SUCCESS, 60, "Existing report", "report-success");

        var inserted = planSteps.findById(result.insertedStepIds().getFirst()).orElseThrow();
        assertThat(inserted.getStepStatus()).isEqualTo(PlanStepStatus.PENDING);
        assertThat(inserted.getStepType()).isEqualTo(PlanStepType.ANALYZE_FAILURE);
        assertThat(inserted.getStepOrder()).isEqualTo(70);
        assertThat(inserted.getGoal()).contains("Analyze failed step");
        assertThat(inserted.getInputRef())
            .contains("trigger=PLAN_STEP_FAILED")
            .contains("decision=" + result.decisionSummary().get("decisionId"))
            .contains("sourceStep=" + failedSource.getStepId())
            .contains("tool=failure.analyze-execution");
    }

    @Test
    void highRiskFailureAnalysisTriggerSkipsPendingDownstreamAndSafeStopsWithoutInsertedStep() {
        var task = saveTask("task-replan-truncate-safe-stop", TaskStatus.ANALYZING_RESULTS);
        var successBefore = saveStep(task.getTaskId(), "step-safe-stop-success-before", PlanStepType.RETRIEVE_KNOWLEDGE, PlanStepStatus.SUCCESS, 10, "Context ready", "context-ref");
        var failedSource = saveStep(task.getTaskId(), "step-safe-stop-failed-source", PlanStepType.ANALYZE_FAILURE, PlanStepStatus.FAILED, 20, "Analyze failure", "execution-input");
        var pendingDownstream = saveStep(task.getTaskId(), "step-safe-stop-pending-downstream", PlanStepType.GENERATE_CASES, PlanStepStatus.PENDING, 30, "Regenerate cases", "case-input");
        var runningDownstream = saveStep(task.getTaskId(), "step-safe-stop-running-downstream", PlanStepType.EXECUTE_SINGLE, PlanStepStatus.RUNNING, 40, "Executing single case", "single-input");
        var secondPendingDownstream = saveStep(task.getTaskId(), "step-safe-stop-second-pending", PlanStepType.GENERATE_REPORT, PlanStepStatus.PENDING, 50, "Generate report", "report-input");

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.FAILURE_ANALYSIS_HIGH_RISK,
            failedSource.getStepId(),
            failedOutcome("Failure analysis marked downstream recovery high risk", "executionRecord:exec-safe-stop-1", "High-risk analysis requires safe stop"),
            Map.of(),
            false,
            failureAnalysisPolicy(),
            ContextBundleSummary.of("High-risk failure evidence references executionRecord:exec-safe-stop-1", List.of("exec:exec-safe-stop-1"), 0, 0, 200),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.REPLAN.name()))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ReplanningStatus.APPLIED);
        assertThat(result.skippedStepIds())
            .containsExactly(pendingDownstream.getStepId(), secondPendingDownstream.getStepId());
        assertThat(result.insertedStepIds()).isEmpty();
        assertThat(result.blockers()).containsExactly("High-risk analysis requires safe stop");
        assertThat(result.planMutationSummary())
            .containsEntry("mutationApplied", true)
            .containsEntry("mutationType", PlannerAction.REPLAN.name())
            .containsEntry("safeStop", true)
            .containsEntry("sourceStepId", failedSource.getStepId())
            .containsEntry("trigger", ReplanningTrigger.FAILURE_ANALYSIS_HIGH_RISK.name());
        assertThat((String) result.planMutationSummary().get("reason")).contains("safe stop");

        assertStepUnchanged(successBefore.getStepId(), PlanStepStatus.SUCCESS, 10, "Context ready", "context-ref");
        assertStepUnchanged(failedSource.getStepId(), PlanStepStatus.FAILED, 20, "Analyze failure", "execution-input");
        assertStepUnchanged(pendingDownstream.getStepId(), PlanStepStatus.SKIPPED, 30, "Regenerate cases", "case-input");
        assertStepUnchanged(runningDownstream.getStepId(), PlanStepStatus.RUNNING, 40, "Executing single case", "single-input");
        assertStepUnchanged(secondPendingDownstream.getStepId(), PlanStepStatus.SKIPPED, 50, "Generate report", "report-input");
    }

    private AgentPolicy failureAnalysisPolicy() {
        return AgentPolicy.v2Phase2Default()
            .withTaskPhase(AgentTaskPhase.FAILURE_ANALYSIS)
            .withWorkflowMode(AgentWorkflowMode.AUTOMATIC);
    }

    private StepOutcome failedOutcome(String summary, String resultRef, String blocker) {
        return new StepOutcome(
            PlanStepStatus.FAILED,
            TaskStatus.FAILED,
            summary,
            List.of(resultRef),
            null,
            List.of(blocker),
            true
        );
    }

    private Task saveTask(String taskId, TaskStatus status) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Truncate-and-replan recovery " + taskId);
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

    private PlanStep saveStep(
        String taskId,
        String stepId,
        PlanStepType type,
        PlanStepStatus status,
        int order,
        String goal,
        String inputRef
    ) {
        var step = new PlanStep();
        step.setStepId(stepId);
        step.setTaskId(taskId);
        step.setStepType(type);
        step.setStepStatus(status);
        step.setStepOrder(order);
        step.setGoal(goal);
        step.setInputRef(inputRef);
        step.setRetryCount(0);
        return planSteps.save(step);
    }

    private void assertStepUnchanged(String stepId, PlanStepStatus status, int order, String goal, String inputRef) {
        var step = planSteps.findById(stepId).orElseThrow();
        assertThat(step.getStepStatus()).isEqualTo(status);
        assertThat(step.getStepOrder()).isEqualTo(order);
        assertThat(step.getGoal()).isEqualTo(goal);
        assertThat(step.getInputRef()).isEqualTo(inputRef);
    }
}
