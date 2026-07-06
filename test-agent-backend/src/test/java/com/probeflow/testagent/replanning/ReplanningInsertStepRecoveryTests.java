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
class ReplanningInsertStepRecoveryTests {

    @Autowired
    private ReplanningApplicationService replanning;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private EntityManager entityManager;

    @Test
    void planStepFailedTriggerInsertsPendingAnalyzeFailureStepAndPreservesHistory() {
        var task = saveTask("task-replan-insert-failure", TaskStatus.ANALYZING_RESULTS);
        var success = saveStep(task.getTaskId(), "step-insert-success", PlanStepType.RETRIEVE_KNOWLEDGE, PlanStepStatus.SUCCESS, 10, "Context ready", "context-ref");
        var failed = saveStep(task.getTaskId(), "step-insert-failed", PlanStepType.EXECUTE_BATCH, PlanStepStatus.FAILED, 20, "Execute batch", "execution-input");
        var running = saveStep(task.getTaskId(), "step-insert-running", PlanStepType.GENERATE_REPORT, PlanStepStatus.RUNNING, 30, "Generate report", "report-input");
        var pending = saveStep(task.getTaskId(), "step-insert-pending", PlanStepType.GENERATE_CASES, PlanStepStatus.PENDING, 40, "Generate cases", "case-input");

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.PLAN_STEP_FAILED,
            failed.getStepId(),
            new StepOutcome(
                PlanStepStatus.FAILED,
                TaskStatus.FAILED,
                "Batch execution failed with assertion mismatch",
                List.of("executionRecord:exec-insert-1"),
                null,
                List.of("Assertion failed on auth boundary"),
                true
            ),
            Map.of(),
            false,
            AgentPolicy.v2Phase2Default()
                .withTaskPhase(AgentTaskPhase.FAILURE_ANALYSIS)
                .withWorkflowMode(AgentWorkflowMode.AUTOMATIC),
            ContextBundleSummary.of("Failure evidence references executionRecord:exec-insert-1", List.of("exec:exec-insert-1"), 0, 0, 200),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.INSERT_STEP.name()))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ReplanningStatus.APPLIED);
        assertThat(result.insertedStepIds()).hasSize(1);
        assertThat(result.skippedStepIds()).isEmpty();
        assertThat(result.decisionSummary())
            .containsEntry("action", PlannerAction.INSERT_STEP.name())
            .containsEntry("proposedPlanStepType", PlanStepType.ANALYZE_FAILURE.name());
        assertThat(result.policySummary()).containsEntry("validationStatus", PolicyValidationStatus.ALLOWED.name());
        assertThat(result.planMutationSummary())
            .containsEntry("mutationApplied", true)
            .containsEntry("mutationType", PlannerAction.INSERT_STEP.name())
            .containsEntry("insertedStepType", PlanStepType.ANALYZE_FAILURE.name())
            .containsEntry("sourceStepId", failed.getStepId())
            .containsEntry("trigger", ReplanningTrigger.PLAN_STEP_FAILED.name());

        assertStepUnchanged(success.getStepId(), PlanStepStatus.SUCCESS, 10, "Context ready", "context-ref");
        assertStepUnchanged(failed.getStepId(), PlanStepStatus.FAILED, 20, "Execute batch", "execution-input");
        assertStepUnchanged(running.getStepId(), PlanStepStatus.RUNNING, 30, "Generate report", "report-input");
        assertStepUnchanged(pending.getStepId(), PlanStepStatus.PENDING, 40, "Generate cases", "case-input");

        var inserted = planSteps.findById(result.insertedStepIds().getFirst()).orElseThrow();
        assertThat(inserted.getStepStatus()).isEqualTo(PlanStepStatus.PENDING);
        assertThat(inserted.getStepType()).isEqualTo(PlanStepType.ANALYZE_FAILURE);
        assertThat(inserted.getStepOrder()).isEqualTo(50);
        assertThat(inserted.getGoal()).contains("Analyze failed step");
        assertThat(inserted.getInputRef())
            .contains("trigger=PLAN_STEP_FAILED")
            .contains("decision=" + result.decisionSummary().get("decisionId"))
            .contains("sourceStep=" + failed.getStepId())
            .contains("tool=failure.analyze-execution");
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(task.getTaskId()))
            .extracting(PlanStep::getStepOrder)
            .containsExactly(10, 20, 30, 40, 50);
    }

    @Test
    void contextMissingTriggerInsertsPendingRetrieveKnowledgeStepWithTraceableAudit() {
        var task = saveTask("task-replan-insert-context", TaskStatus.ANALYZING);
        var source = saveStep(task.getTaskId(), "step-insert-context-source", PlanStepType.GENERATE_CASES, PlanStepStatus.PENDING, 10, "Generate cases", "api-spec:orders");

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.CONTEXT_MISSING,
            source.getStepId(),
            StepOutcome.blocked("Context bundle is missing", List.of("No RAG citations found")),
            Map.of(),
            false,
            AgentPolicy.v2Phase2Default()
                .withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING)
                .withWorkflowMode(AgentWorkflowMode.AUTOMATIC),
            ContextBundleSummary.empty(),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.INSERT_STEP.name()))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ReplanningStatus.APPLIED);
        assertThat(result.insertedStepIds()).hasSize(1);
        assertThat(result.policySummary()).containsEntry("validationStatus", PolicyValidationStatus.ALLOWED.name());
        assertThat(result.planMutationSummary())
            .containsEntry("insertedStepType", PlanStepType.RETRIEVE_KNOWLEDGE.name())
            .containsEntry("sourceStepId", source.getStepId());

        assertStepUnchanged(source.getStepId(), PlanStepStatus.PENDING, 10, "Generate cases", "api-spec:orders");
        var inserted = planSteps.findById(result.insertedStepIds().getFirst()).orElseThrow();
        assertThat(inserted.getStepStatus()).isEqualTo(PlanStepStatus.PENDING);
        assertThat(inserted.getStepType()).isEqualTo(PlanStepType.RETRIEVE_KNOWLEDGE);
        assertThat(inserted.getStepOrder()).isEqualTo(20);
        assertThat(inserted.getGoal()).contains("Retrieve missing context");
        assertThat(inserted.getInputRef())
            .contains("trigger=CONTEXT_MISSING")
            .contains("decision=" + result.decisionSummary().get("decisionId"))
            .contains("sourceStep=" + source.getStepId())
            .contains("tool=knowledge.retrieve-context");
    }

    private Task saveTask(String taskId, TaskStatus status) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Insert-step replanning " + taskId);
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
