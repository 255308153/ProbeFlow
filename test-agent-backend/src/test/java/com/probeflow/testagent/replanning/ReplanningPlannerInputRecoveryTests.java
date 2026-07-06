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
class ReplanningPlannerInputRecoveryTests {

    @Autowired
    private ReplanningApplicationService replanning;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Test
    void planStepFailedTriggerBuildsPlannerInputWithOutcomeSourceStepBlockersResultRefsAndTools() {
        var task = saveTask("task-replan-input-failed", TaskStatus.ANALYZING_RESULTS);
        saveStep(task.getTaskId(), "step-context-ok", PlanStepType.RETRIEVE_KNOWLEDGE, PlanStepStatus.SUCCESS, 10);
        var failed = saveStep(task.getTaskId(), "step-generate-failed", PlanStepType.GENERATE_CASES, PlanStepStatus.FAILED, 20);
        saveStep(task.getTaskId(), "step-execute-pending", PlanStepType.EXECUTE_BATCH, PlanStepStatus.PENDING, 30);
        var policy = AgentPolicy.v2Phase2Default()
            .withTaskPhase(AgentTaskPhase.TEST_DESIGN)
            .withWorkflowMode(AgentWorkflowMode.REVIEW_REQUIRED);
        var outcome = new StepOutcome(
            PlanStepStatus.FAILED,
            TaskStatus.FAILED,
            "Case generation failed after execution evidence changed",
            List.of("executionRecord:exec-1"),
            "report:partial-1",
            List.of("Missing auth edge case", "Assertion mismatch"),
            true
        );

        var input = replanning.buildPlannerInput(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.PLAN_STEP_FAILED,
            failed.getStepId(),
            outcome,
            Map.of(),
            false,
            policy,
            ContextBundleSummary.of("Auth context bundle", List.of("wiki/auth"), 2, 1, 600),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.REPLAN.name()))
        ));

        assertThat(input.taskState().taskId()).isEqualTo(task.getTaskId());
        assertThat(input.taskState().taskStatus()).isEqualTo("ANALYZING_RESULTS");
        assertThat(input.taskState().currentPlanStepId()).isEqualTo(failed.getStepId());
        assertThat(input.taskState().remainingStepTypes()).containsExactly("GENERATE_CASES", "EXECUTE_BATCH");
        assertThat(input.currentPhase()).isEqualTo(AgentTaskPhase.TEST_DESIGN);
        assertThat(input.workflowMode()).isEqualTo(AgentWorkflowMode.REVIEW_REQUIRED);
        assertThat(input.contextSummary().summary()).isEqualTo("Auth context bundle");
        assertThat(input.contextSummary().citationRefs()).containsExactly("wiki/auth");
        assertThat(input.lastStepOutcome().stepStatus()).isEqualTo("FAILED");
        assertThat(input.lastStepOutcome().taskStatus()).isEqualTo("FAILED");
        assertThat(input.lastStepOutcome().summary()).contains("Case generation failed");
        assertThat(input.lastStepOutcome().resultRefs()).containsExactly("executionRecord:exec-1", "report:partial-1");
        assertThat(input.lastStepOutcome().blockers()).containsExactly("Missing auth edge case", "Assertion mismatch");
        assertThat(input.lastStepOutcome().sourceStepId()).isEqualTo(failed.getStepId());
        assertThat(input.lastStepOutcome().sourceStepType()).isEqualTo("GENERATE_CASES");
        assertThat(input.lastStepOutcome().sourceStepStatus()).isEqualTo("FAILED");
        assertThat(input.availableTools()).extracting(tool -> tool.name())
            .contains("testcase.generate-drafts", "knowledge.retrieve-context");
        assertThat(input.constraints()).extracting(PlannerConstraint::code)
            .contains(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, "REPLANNING_TRIGGER", "REPLANNING_SOURCE_STEP");

        var decision = new FakeControlledPlanner().plan(input);
        assertThat(decision.action()).isEqualTo(PlannerAction.REPLAN);
        assertThat(decision.blockers()).containsExactly("Missing auth edge case", "Assertion mismatch");
    }

    @Test
    void executionReadinessMissingTriggerBuildsPlannerInputWithHumanReadableBlockers() {
        var task = saveTask("task-replan-input-readiness", TaskStatus.EXECUTING);
        var execute = saveStep(task.getTaskId(), "step-execute-blocked", PlanStepType.EXECUTE_BATCH, PlanStepStatus.PENDING, 10);

        var input = replanning.buildPlannerInput(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.EXECUTION_READINESS_MISSING,
            execute.getStepId(),
            StepOutcome.blocked("Execution readiness is incomplete", List.of("Missing targetEnvironment", "Missing authToken")),
            Map.of(),
            false,
            AgentPolicy.v2Phase2Default()
                .withTaskPhase(AgentTaskPhase.EXECUTION)
                .withWorkflowMode(AgentWorkflowMode.AUTOMATIC),
            ContextBundleSummary.of("Approved cases are ready but environment is not configured", List.of("case:approved"), 0, 1, 300),
            List.of()
        ));

        assertThat(input.currentPhase()).isEqualTo(AgentTaskPhase.EXECUTION);
        assertThat(input.workflowMode()).isEqualTo(AgentWorkflowMode.AUTOMATIC);
        assertThat(input.lastStepOutcome().blockers()).containsExactly("Missing targetEnvironment", "Missing authToken");
        assertThat(input.lastStepOutcome().sourceStepType()).isEqualTo("EXECUTE_BATCH");
        assertThat(input.availableTools()).extracting(tool -> tool.name()).contains("http.execute-approved-case");
    }

    @Test
    void contextMissingTriggerKeepsEmptyContextSummaryAndPlannerSafeKnowledgeToolVisibility() {
        var task = saveTask("task-replan-input-context", TaskStatus.ANALYZING);
        var generate = saveStep(task.getTaskId(), "step-generate-waiting-context", PlanStepType.GENERATE_CASES, PlanStepStatus.PENDING, 10);

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.CONTEXT_MISSING,
            generate.getStepId(),
            StepOutcome.blocked("Context bundle is missing", List.of("No RAG citations found")),
            Map.of(),
            false,
            AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING),
            ContextBundleSummary.empty(),
            List.of()
        ));
        var input = replanning.buildPlannerInput(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.CONTEXT_MISSING,
            generate.getStepId(),
            StepOutcome.blocked("Context bundle is missing", List.of("No RAG citations found")),
            Map.of(),
            false,
            AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING),
            ContextBundleSummary.empty(),
            List.of()
        ));

        assertThat(result.status()).isEqualTo(ReplanningStatus.NOOP);
        assertThat(result.decisionSummary())
            .containsEntry("plannerCalled", false)
            .containsEntry("sourceStepType", "GENERATE_CASES")
            .containsEntry("lastStepStatus", "SKIPPED");
        assertThat(input.contextSummary()).isEqualTo(ContextBundleSummary.empty());
        assertThat(input.lastStepOutcome().blockers()).containsExactly("No RAG citations found");
        assertThat(input.availableTools().stream()
            .filter(tool -> tool.name().equals("knowledge.retrieve-context"))
            .findFirst()
            .orElseThrow()
            .blocked()).isFalse();
    }

    private Task saveTask(String taskId, TaskStatus status) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Replanning input " + taskId);
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
        int order
    ) {
        var step = new PlanStep();
        step.setStepId(stepId);
        step.setTaskId(taskId);
        step.setStepType(type);
        step.setStepStatus(status);
        step.setStepOrder(order);
        step.setGoal("Run " + type);
        step.setInputRef("input:" + stepId);
        return planSteps.save(step);
    }
}
