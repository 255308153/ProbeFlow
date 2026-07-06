package com.probeflow.testagent.humanintheloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.AgentWorkflowMode;
import com.probeflow.testagent.controlledplanner.ContextBundleSummary;
import com.probeflow.testagent.controlledplanner.FakeControlledPlanner;
import com.probeflow.testagent.controlledplanner.FakePlannerScenario;
import com.probeflow.testagent.controlledplanner.PlannerConstraint;
import com.probeflow.testagent.orchestration.StepOutcome;
import com.probeflow.testagent.replanning.ReplanningApplicationService;
import com.probeflow.testagent.replanning.ReplanningRequest;
import com.probeflow.testagent.replanning.ReplanningStatus;
import com.probeflow.testagent.replanning.ReplanningTrigger;
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
class PlannerClarificationHumanWorkflowTests {

    @Autowired
    private ReplanningApplicationService replanning;

    @Autowired
    private HumanInTheLoopApplicationService humanInTheLoop;

    @Autowired
    private HumanReviewRequestRepository humanRequests;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private EntityManager entityManager;

    @Test
    void plannerClarificationRequestContainsQuestionOptionsSchemaAndDeduplicatesPendingQuestion() {
        var task = saveTask("task-hitl-clar-create", TaskStatus.FAILED, Map.of());
        var failed = saveStep(task.getTaskId(), "step-clar-create-failed", PlanStepType.EXECUTE_BATCH, PlanStepStatus.FAILED, 10);

        var first = replanning.replan(clarificationRequest(task.getTaskId(), failed.getStepId()));
        var second = replanning.replan(clarificationRequest(task.getTaskId(), failed.getStepId()));

        entityManager.flush();
        entityManager.clear();

        assertThat(first.status()).isEqualTo(ReplanningStatus.WAITING_FOR_HUMAN);
        assertThat(second.status()).isIn(ReplanningStatus.WAITING_FOR_HUMAN, ReplanningStatus.NOOP);
        var requests = humanInTheLoop.requestsForTask(task.getTaskId());
        assertThat(requests).hasSize(1);
        var request = requests.getFirst();
        assertThat(request.getRequestType()).isEqualTo(HumanRequestType.PLANNER_CLARIFICATION);
        assertThat(request.getStatus()).isEqualTo(HumanRequestStatus.PENDING);
        assertThat(request.getWaitingReason())
            .contains("Missing target environment")
            .contains("Which configured environment should the next suggested step use?");
        assertThat(request.getRequiredInputSchema())
            .extracting(field -> field.get("name"))
            .containsExactly("targetEnvironment");
        assertThat(request.getMetadata())
            .containsEntry("question", "Which configured environment should the next suggested step use?");
        assertThat(metadataList(request.getMetadata().get("options")))
            .containsExactly("staging", "qa", "prod-readonly");
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
    }

    @Test
    void clarificationAnswerIsConsumedAndPassedToRecoveryReplanningAsPlannerConstraint() {
        var task = saveTask("task-hitl-clar-answer", TaskStatus.FAILED, Map.of());
        var failed = saveStep(task.getTaskId(), "step-clar-answer-failed", PlanStepType.EXECUTE_BATCH, PlanStepStatus.FAILED, 10);
        saveStep(task.getTaskId(), "step-clar-answer-pending", PlanStepType.GENERATE_REPORT, PlanStepStatus.PENDING, 20);
        replanning.replan(clarificationRequest(task.getTaskId(), failed.getStepId()));
        var request = humanInTheLoop.activeRequestForTask(task.getTaskId()).orElseThrow();

        var clarification = humanInTheLoop.applyPlannerClarificationDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.PROVIDE_INPUT,
            "api-owner",
            "Use isolated staging because qa is currently reserved.",
            Map.of(
                "targetEnvironment", "staging",
                "token", "secret-token-for-audit-mask"
            )
        ));

        var recovery = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.HUMAN_INPUT_REQUIRED,
            failed.getStepId(),
            new StepOutcome(
                PlanStepStatus.SKIPPED,
                TaskStatus.ANALYZING_RESULTS,
                "Planner clarification answered",
                List.of("apiSpec:api-clar-answer"),
                null,
                List.of("Human clarification supplied"),
                false
            ),
            clarification.humanInput(),
            false,
            AgentPolicy.v2Phase2Default()
                .withTaskPhase(AgentTaskPhase.TEST_DESIGN)
                .withWorkflowMode(AgentWorkflowMode.AUTOMATIC),
            ContextBundleSummary.of("Human clarification was supplied for recovery", List.of("human:" + request.getRequestId()), 0, 0, 200),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.INSERT_STEP.name()))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(clarification.status()).isEqualTo(HumanPlannerClarificationStatus.APPLIED);
        assertThat(clarification.recoveryTrigger()).isEqualTo(ReplanningTrigger.HUMAN_INPUT_REQUIRED.name());
        assertThat(clarification.request().getStatus()).isEqualTo(HumanRequestStatus.CONSUMED);
        assertThat(metadataMap(clarification.auditSummary().get("payloadSummary")))
            .containsEntry("targetEnvironment", "staging")
            .containsEntry("token", HumanInTheLoopApplicationService.MASKED_VALUE);
        assertThat(metadataMap(metadataMap(clarification.humanInput().get("plannerClarification")).get("payloadSummary")))
            .containsEntry("targetEnvironment", "staging")
            .containsEntry("token", HumanInTheLoopApplicationService.MASKED_VALUE);

        var savedTask = tasks.findById(task.getTaskId()).orElseThrow();
        assertThat(metadataMap(savedTask.getMetadata().get("plannerClarificationContext")))
            .containsEntry("targetEnvironment", "staging")
            .containsEntry("token", "secret-token-for-audit-mask");
        assertThat(metadataMap(savedTask.getMetadata().get("plannerClarificationContextSummary")))
            .containsEntry("token", HumanInTheLoopApplicationService.MASKED_VALUE);

        assertThat(recovery.status()).isEqualTo(ReplanningStatus.APPLIED);
        assertThat(constraintDescriptions(recovery.decisionSummary(), "PLANNER_CLARIFICATION_ANSWER"))
            .singleElement()
            .satisfies(description -> assertThat(description)
                .contains("targetEnvironment=staging")
                .contains("token=***MASKED***"));
        assertThat(recovery.insertedStepIds()).hasSize(1);
    }

    @Test
    void emptyOrSchemaInvalidClarificationAnswerIsRejectedWithoutConsumingRequest() {
        var task = saveTask("task-hitl-clar-invalid", TaskStatus.FAILED, Map.of());
        var failed = saveStep(task.getTaskId(), "step-clar-invalid-failed", PlanStepType.EXECUTE_BATCH, PlanStepStatus.FAILED, 10);
        replanning.replan(clarificationRequest(task.getTaskId(), failed.getStepId()));
        var request = humanInTheLoop.activeRequestForTask(task.getTaskId()).orElseThrow();

        var empty = humanInTheLoop.applyPlannerClarificationDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.PROVIDE_INPUT,
            "api-owner",
            "No answer yet.",
            Map.of()
        ));
        var invalidType = humanInTheLoop.applyPlannerClarificationDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.PROVIDE_INPUT,
            "api-owner",
            "This should still fail schema validation.",
            Map.of("targetEnvironment", List.of("staging"))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(empty.status()).isEqualTo(HumanPlannerClarificationStatus.REJECTED);
        assertThat(empty.blockers()).containsExactly("Planner clarification answer is required");
        assertThat(invalidType.status()).isEqualTo(HumanPlannerClarificationStatus.REJECTED);
        assertThat(invalidType.blockers()).containsExactly("Invalid human input type for targetEnvironment: expected string");
        assertThat(humanRequests.findById(request.getRequestId()).orElseThrow().getStatus()).isEqualTo(HumanRequestStatus.PENDING);
        assertThat(humanInTheLoop.decisionsForTask(task.getTaskId())).isEmpty();
    }

    private ReplanningRequest clarificationRequest(String taskId, String sourceStepId) {
        return new ReplanningRequest(
            taskId,
            ReplanningTrigger.PLAN_STEP_FAILED,
            sourceStepId,
            StepOutcome.blocked("Planner needs business clarification", List.of("Ambiguous recovery target")),
            Map.of(),
            false,
            AgentPolicy.v2Phase2Default()
                .withTaskPhase(AgentTaskPhase.EXECUTION)
                .withWorkflowMode(AgentWorkflowMode.AUTOMATIC),
            ContextBundleSummary.of("Failure context has multiple possible recovery targets", List.of("exec:" + taskId), 0, 0, 200),
            List.of(
                PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.WAIT_FOR_HUMAN.name()),
                PlannerConstraint.of("PLANNER_CLARIFICATION_OPTIONS", "staging, qa, prod-readonly")
            )
        );
    }

    private Task saveTask(String taskId, TaskStatus status, Map<String, Object> metadata) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Planner clarification " + taskId);
        task.setStatus(status);
        task.setSourceType(TaskSourceType.OPENAPI);
        task.setSourceRef("source-" + taskId);
        task.setTargetApiSpecIds(List.of("api-" + taskId));
        task.setPromotionMode(PromotionMode.AUTO);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase6");
        task.setMetadata(metadata);
        return tasks.save(task);
    }

    private PlanStep saveStep(String taskId, String stepId, PlanStepType type, PlanStepStatus status, int order) {
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

    private List<String> constraintDescriptions(Map<String, Object> decisionSummary, String code) {
        return constraintMaps(decisionSummary).stream()
            .filter(constraint -> code.equals(constraint.get("code")))
            .map(constraint -> String.valueOf(constraint.get("description")))
            .toList();
    }

    private List<Map<String, Object>> constraintMaps(Map<String, Object> decisionSummary) {
        if (!(decisionSummary.get("constraints") instanceof List<?> constraints)) {
            return List.of();
        }
        return constraints.stream()
            .map(this::metadataMap)
            .toList();
    }

    private List<String> metadataList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataMap(Object value) {
        return (Map<String, Object>) value;
    }
}
