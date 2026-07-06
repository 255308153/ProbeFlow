package com.probeflow.testagent.replanning;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.AgentWorkflowMode;
import com.probeflow.testagent.controlledplanner.ContextBundleSummary;
import com.probeflow.testagent.controlledplanner.FakeControlledPlanner;
import com.probeflow.testagent.controlledplanner.FakePlannerScenario;
import com.probeflow.testagent.controlledplanner.PlannerConstraint;
import com.probeflow.testagent.orchestration.StepOutcome;
import com.probeflow.testagent.policyvalidator.PolicyValidationReasonCode;
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
class ReplanningPolicyGatedDecisionTests {

    @Autowired
    private ReplanningApplicationService replanning;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private EntityManager entityManager;

    @Test
    void policyBlockedPlannerDecisionIsRejectedWithoutTaskOrPlanMutation() {
        var task = saveTask("task-replan-policy-blocked", TaskStatus.ANALYZING);
        var step = saveStep(task.getTaskId(), "step-policy-blocked", PlanStepType.GENERATE_CASES, PlanStepStatus.PENDING, 10);

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.CONTEXT_MISSING,
            step.getStepId(),
            StepOutcome.blocked("Context is absent", List.of("No auth examples found")),
            Map.of(),
            false,
            AgentPolicy.v2Phase2Default()
                .withTaskPhase(AgentTaskPhase.TEST_DESIGN)
                .withWorkflowMode(AgentWorkflowMode.AUTOMATIC),
            ContextBundleSummary.empty(),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.INSERT_STEP.name()))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ReplanningStatus.REJECTED_BY_POLICY);
        assertThat(result.decisionSummary())
            .containsEntry("plannerCalled", true)
            .containsEntry("action", "INSERT_STEP")
            .containsEntry("proposedPlanStepType", "GENERATE_CASES");
        assertThat(result.policySummary())
            .containsEntry("validationStatus", PolicyValidationStatus.BLOCKED.name())
            .containsEntry("reasonCode", PolicyValidationReasonCode.MISSING_API_SPEC.name());
        assertThat(result.planMutationSummary()).containsEntry("mutationApplied", false);
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.ANALYZING);
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getMetadata()).isEmpty();
        assertThat(planSteps.findById(step.getStepId()).orElseThrow().getStepStatus()).isEqualTo(PlanStepStatus.PENDING);
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(task.getTaskId())).hasSize(1);
    }

    @Test
    void requiresHumanConfirmationPausesTaskAndRecordsRequiredHumanInputWithoutChangingPlan() {
        var task = saveTask("task-replan-policy-human", TaskStatus.EXECUTING);
        var step = saveStep(task.getTaskId(), "step-policy-human", PlanStepType.EXECUTE_BATCH, PlanStepStatus.PENDING, 10);

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.EXECUTION_READINESS_MISSING,
            step.getStepId(),
            StepOutcome.blocked("Execution readiness is incomplete", List.of("Missing targetEnvironment")),
            Map.of(),
            false,
            AgentPolicy.v2Phase2Default()
                .withTaskPhase(AgentTaskPhase.EXECUTION)
                .withWorkflowMode(AgentWorkflowMode.AUTOMATIC),
            ContextBundleSummary.of("Approved case exists but environment is not configured", List.of("case:approved"), 0, 1, 200),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.WAIT_FOR_HUMAN.name()))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ReplanningStatus.WAITING_FOR_HUMAN);
        assertThat(result.decisionSummary())
            .containsEntry("plannerCalled", true)
            .containsEntry("action", "WAIT_FOR_HUMAN");
        assertThat(result.policySummary())
            .containsEntry("validationStatus", PolicyValidationStatus.REQUIRES_HUMAN_CONFIRMATION.name())
            .containsEntry("reasonCode", PolicyValidationReasonCode.HUMAN_INPUT_REQUIRED.name());
        assertThat(result.planMutationSummary())
            .containsEntry("mutationApplied", true)
            .containsEntry("taskStatus", TaskStatus.WAITING_FOR_REVIEW.name());
        assertThat(result.insertedStepIds()).isEmpty();
        assertThat(result.skippedStepIds()).isEmpty();

        var pausedTask = tasks.findById(task.getTaskId()).orElseThrow();
        assertThat(pausedTask.getStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
        assertThat(pausedTask.getMetadata()).containsKeys("requiredHumanInput", "lastReplanning");
        assertThat(metadataMap(pausedTask.getMetadata().get("requiredHumanInput")))
            .containsEntry("reason", "Missing target environment")
            .containsEntry("question", "Which configured environment should the next suggested step use?");
        assertThat(metadataMap(pausedTask.getMetadata().get("lastReplanning")))
            .containsEntry("trigger", ReplanningTrigger.EXECUTION_READINESS_MISSING.name())
            .containsEntry("policyStatus", PolicyValidationStatus.REQUIRES_HUMAN_CONFIRMATION.name())
            .containsEntry("status", ReplanningStatus.WAITING_FOR_HUMAN.name());
        assertThat(planSteps.findById(step.getStepId()).orElseThrow().getStepStatus()).isEqualTo(PlanStepStatus.PENDING);
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(task.getTaskId())).hasSize(1);
    }

    @Test
    void safeContinueKeepsTemplatePlanAndReturnsDecisionAndPolicyAuditSummaries() {
        var task = saveTask("task-replan-policy-continue", TaskStatus.ANALYZING);
        var step = saveStep(task.getTaskId(), "step-policy-continue", PlanStepType.RETRIEVE_KNOWLEDGE, PlanStepStatus.PENDING, 10);

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.CONTEXT_MISSING,
            step.getStepId(),
            StepOutcome.blocked("Context bundle is incomplete", List.of("Need one more citation")),
            Map.of(),
            false,
            AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING),
            ContextBundleSummary.empty(),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.CONTINUE.name()))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ReplanningStatus.NOOP);
        assertThat(result.decisionSummary())
            .containsEntry("plannerCalled", true)
            .containsEntry("action", "CONTINUE")
            .containsEntry("sourceStepId", step.getStepId());
        assertThat(result.policySummary())
            .containsEntry("validationStatus", PolicyValidationStatus.ALLOWED.name())
            .containsEntry("reasonCode", PolicyValidationReasonCode.SAFE_CONTINUE.name());
        assertThat(result.planMutationSummary()).containsEntry("mutationApplied", false);
        assertThat(result.insertedStepIds()).isEmpty();
        assertThat(result.skippedStepIds()).isEmpty();
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.ANALYZING);
        assertThat(planSteps.findById(step.getStepId()).orElseThrow().getStepStatus()).isEqualTo(PlanStepStatus.PENDING);
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(task.getTaskId())).hasSize(1);
    }

    private Task saveTask(String taskId, TaskStatus status) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Policy gated replanning " + taskId);
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
        step.setRetryCount(0);
        return planSteps.save(step);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataMap(Object value) {
        return (Map<String, Object>) value;
    }
}
