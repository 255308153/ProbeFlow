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
class ReplanningIdempotencyAttemptGuardTests {

    @Autowired
    private ReplanningApplicationService replanning;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private EntityManager entityManager;

    @Test
    void repeatedSameTriggerKeyReturnsIdempotentNoopAndDoesNotInsertDuplicateStep() {
        var task = saveTask("task-replan-idempotent-duplicate", TaskStatus.ANALYZING, Map.of());
        var source = saveStep(task.getTaskId(), "step-idempotent-source", PlanStepType.GENERATE_CASES, PlanStepStatus.PENDING, 10, 0);
        var request = contextMissingInsertRequest(task.getTaskId(), source.getStepId());

        var first = replanning.replan(request);
        entityManager.flush();
        entityManager.clear();
        var second = replanning.replan(request);
        entityManager.flush();
        entityManager.clear();

        assertThat(first.status()).isEqualTo(ReplanningStatus.APPLIED);
        assertThat(first.insertedStepIds()).hasSize(1);
        assertThat(first.planMutationSummary())
            .containsEntry("attemptCount", 1)
            .containsEntry("triggerAttemptCount", 1);
        assertThat(second.status()).isEqualTo(ReplanningStatus.NOOP);
        assertThat(second.insertedStepIds()).containsExactlyElementsOf(first.insertedStepIds());
        assertThat(second.planMutationSummary())
            .containsEntry("mutationApplied", false)
            .containsEntry("idempotent", true)
            .containsEntry("attemptCount", 1)
            .containsEntry("triggerAttemptCount", 1);

        var steps = planSteps.findByTaskIdOrderByStepOrderAsc(task.getTaskId());
        assertThat(steps).hasSize(2);
        assertThat(steps)
            .extracting(PlanStep::getStepType)
            .containsExactly(PlanStepType.GENERATE_CASES, PlanStepType.RETRIEVE_KNOWLEDGE);
        var metadata = tasks.findById(task.getTaskId()).orElseThrow().getMetadata();
        assertThat(metadata).containsEntry("replanningAttemptCount", 1);
        assertThat(metadataMap(metadata.get("replanningAttemptRecords"))).hasSize(1);
    }

    @Test
    void taskAndTriggerAttemptLimitsStopBeforeAnyPlanMutation() {
        var taskLimit = saveTask(
            "task-replan-task-attempt-limit",
            TaskStatus.ANALYZING,
            Map.of("replanningAttemptCount", 5)
        );
        var taskLimitSource = saveStep(taskLimit.getTaskId(), "step-task-attempt-limit", PlanStepType.GENERATE_CASES, PlanStepStatus.PENDING, 10, 0);

        var taskLimited = replanning.replan(contextMissingInsertRequest(taskLimit.getTaskId(), taskLimitSource.getStepId()));

        assertThat(taskLimited.status()).isEqualTo(ReplanningStatus.NOT_TRIGGERABLE);
        assertThat(taskLimited.blockers()).containsExactly("Task replanning attempt limit reached.");
        assertThat(taskLimited.planMutationSummary())
            .containsEntry("mutationApplied", false)
            .containsEntry("attemptCount", 5)
            .containsEntry("maxTaskAttempts", 5);
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(taskLimit.getTaskId())).hasSize(1);

        var triggerLimit = saveTask(
            "task-replan-trigger-attempt-limit",
            TaskStatus.ANALYZING,
            Map.of(
                "replanningAttemptCount", 1,
                "replanningTriggerAttempts", Map.of("CONTEXT_MISSING|step-trigger-attempt-limit", 2)
            )
        );
        var triggerLimitSource = saveStep(triggerLimit.getTaskId(), "step-trigger-attempt-limit", PlanStepType.GENERATE_CASES, PlanStepStatus.PENDING, 10, 0);

        var triggerLimited = replanning.replan(contextMissingInsertRequest(triggerLimit.getTaskId(), triggerLimitSource.getStepId()));

        assertThat(triggerLimited.status()).isEqualTo(ReplanningStatus.NOT_TRIGGERABLE);
        assertThat(triggerLimited.blockers()).containsExactly("Trigger replanning attempt limit reached.");
        assertThat(triggerLimited.planMutationSummary())
            .containsEntry("mutationApplied", false)
            .containsEntry("triggerAttemptCount", 2)
            .containsEntry("maxTriggerAttempts", 2);
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(triggerLimit.getTaskId())).hasSize(1);
    }

    @Test
    void terminalTaskAndSourceRetryGuardsStopUnsafeReplanning() {
        var completed = saveTask("task-replan-completed-guard", TaskStatus.COMPLETED, Map.of());
        var cancelled = saveTask("task-replan-cancelled-guard", TaskStatus.CANCELLED, Map.of());

        assertThat(replanning.replan(ReplanningRequest.of(completed.getTaskId(), ReplanningTrigger.CONTEXT_MISSING)).status())
            .isEqualTo(ReplanningStatus.NOT_TRIGGERABLE);
        assertThat(replanning.replan(ReplanningRequest.of(cancelled.getTaskId(), ReplanningTrigger.CONTEXT_MISSING)).status())
            .isEqualTo(ReplanningStatus.NOT_TRIGGERABLE);

        var retryLimited = saveTask("task-replan-source-retry-limit", TaskStatus.ANALYZING_RESULTS, Map.of());
        var source = saveStep(retryLimited.getTaskId(), "step-source-retry-limit", PlanStepType.EXECUTE_BATCH, PlanStepStatus.FAILED, 10, 3);

        var result = replanning.replan(new ReplanningRequest(
            retryLimited.getTaskId(),
            ReplanningTrigger.PLAN_STEP_FAILED,
            source.getStepId(),
            failedOutcome("Execution failed repeatedly", "executionRecord:exec-retry-limit", "Retry limit reached"),
            Map.of(),
            false,
            failureAnalysisPolicy(),
            ContextBundleSummary.of("Failure context", List.of("exec:exec-retry-limit"), 0, 0, 200),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.INSERT_STEP.name()))
        ));

        assertThat(result.status()).isEqualTo(ReplanningStatus.NOT_TRIGGERABLE);
        assertThat(result.blockers()).containsExactly("Source step retry count reached limit: 3");
        assertThat(result.planMutationSummary())
            .containsEntry("retryCount", 3)
            .containsEntry("maxRetryCount", 3);
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(retryLimited.getTaskId())).hasSize(1);
    }

    @Test
    void failedTaskRequiresExplicitRecoveryTriggerBeforeItCanRecover() {
        var ordinaryFailed = saveTask("task-replan-failed-ordinary", TaskStatus.FAILED, Map.of());
        var ordinarySource = saveStep(ordinaryFailed.getTaskId(), "step-failed-ordinary-source", PlanStepType.GENERATE_CASES, PlanStepStatus.PENDING, 10, 0);

        var ordinary = replanning.replan(contextMissingInsertRequest(ordinaryFailed.getTaskId(), ordinarySource.getStepId()));

        assertThat(ordinary.status()).isEqualTo(ReplanningStatus.NOT_TRIGGERABLE);
        assertThat(ordinary.blockers()).containsExactly("Failed task requires an explicit recovery trigger before replanning.");
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(ordinaryFailed.getTaskId())).hasSize(1);

        var recoverableFailed = saveTask("task-replan-failed-explicit", TaskStatus.FAILED, Map.of());
        var failedSource = saveStep(recoverableFailed.getTaskId(), "step-failed-explicit-source", PlanStepType.EXECUTE_BATCH, PlanStepStatus.FAILED, 10, 0);

        var explicit = replanning.replan(new ReplanningRequest(
            recoverableFailed.getTaskId(),
            ReplanningTrigger.PLAN_STEP_FAILED,
            failedSource.getStepId(),
            failedOutcome("Batch execution failed", "executionRecord:exec-explicit-recovery", "Failure signal available"),
            Map.of(),
            false,
            failureAnalysisPolicy(),
            ContextBundleSummary.of("Failure context", List.of("exec:exec-explicit-recovery"), 0, 0, 200),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.INSERT_STEP.name()))
        ));

        assertThat(explicit.status()).isEqualTo(ReplanningStatus.APPLIED);
        assertThat(explicit.insertedStepIds()).hasSize(1);
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(recoverableFailed.getTaskId())).hasSize(2);
    }

    @Test
    void failedMutationRollsBackAttemptAuditAndLeavesPlanUnchanged() {
        var task = saveTask("task-replan-illegal-insert", TaskStatus.ANALYZING, Map.of());
        var source = saveStep(task.getTaskId(), "step-illegal-insert-source", PlanStepType.GENERATE_CASES, PlanStepStatus.PENDING, 10, 0);

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
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.ILLEGAL_INSERT_STEP.name()))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ReplanningStatus.FAILED);
        assertThat(result.blockers()).containsExactly("Planner proposed an illegal PlanStepType.");
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(task.getTaskId())).hasSize(1);
        var metadata = tasks.findById(task.getTaskId()).orElseThrow().getMetadata();
        assertThat(metadata).containsEntry("replanningAttemptCount", 0);
        assertThat(metadataMap(metadata.get("replanningAttemptRecords"))).isEmpty();
    }

    private ReplanningRequest contextMissingInsertRequest(String taskId, String sourceStepId) {
        return new ReplanningRequest(
            taskId,
            ReplanningTrigger.CONTEXT_MISSING,
            sourceStepId,
            StepOutcome.blocked("Context bundle is missing", List.of("No RAG citations found")),
            Map.of(),
            false,
            AgentPolicy.v2Phase2Default()
                .withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING)
                .withWorkflowMode(AgentWorkflowMode.AUTOMATIC),
            ContextBundleSummary.empty(),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.INSERT_STEP.name()))
        );
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

    private Task saveTask(String taskId, TaskStatus status, Map<String, Object> metadata) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Idempotency replanning " + taskId);
        task.setStatus(status);
        task.setSourceType(TaskSourceType.OPENAPI);
        task.setSourceRef("source-" + taskId);
        task.setTargetApiSpecIds(List.of("api-" + taskId));
        task.setPromotionMode(PromotionMode.AUTO);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase5");
        task.setMetadata(metadata);
        return tasks.save(task);
    }

    private PlanStep saveStep(
        String taskId,
        String stepId,
        PlanStepType type,
        PlanStepStatus status,
        int order,
        int retryCount
    ) {
        var step = new PlanStep();
        step.setStepId(stepId);
        step.setTaskId(taskId);
        step.setStepType(type);
        step.setStepStatus(status);
        step.setStepOrder(order);
        step.setGoal("Run " + type);
        step.setInputRef("input:" + stepId);
        step.setRetryCount(retryCount);
        return planSteps.save(step);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
}
