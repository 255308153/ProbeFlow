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
import com.probeflow.testagent.policyvalidator.PolicyValidationReasonCode;
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
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import com.probeflow.testagent.testcasedraft.TestCaseDraft;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
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
class ReplanningHumanRequestCreationTests {

    @Autowired
    private ReplanningApplicationService replanning;

    @Autowired
    private HumanInTheLoopApplicationService humanInTheLoop;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private TestCaseDraftRepository drafts;

    @Autowired
    private EntityManager entityManager;

    @Test
    void replanningWaitForHumanCreatesMissingInputRequestAndReusesSamePendingRequest() {
        var task = saveTask("task-hitl-replan-missing-input", TaskStatus.EXECUTING, PromotionMode.AUTO, Map.of());
        var step = saveStep(task.getTaskId(), "step-hitl-missing-input", PlanStepType.EXECUTE_BATCH, PlanStepStatus.PENDING, 10);

        var first = replanning.replan(waitForHumanRequest(task.getTaskId(), step.getStepId()));
        var second = replanning.replan(waitForHumanRequest(task.getTaskId(), step.getStepId()));

        entityManager.flush();
        entityManager.clear();

        assertThat(first.status()).isEqualTo(ReplanningStatus.WAITING_FOR_HUMAN);
        assertThat(second.status()).isIn(ReplanningStatus.WAITING_FOR_HUMAN, ReplanningStatus.NOOP);
        var requests = humanInTheLoop.requestsForTask(task.getTaskId());
        assertThat(requests).hasSize(1);
        var humanRequest = requests.getFirst();
        assertThat(humanRequest.getRequestType()).isEqualTo(HumanRequestType.MISSING_INPUT);
        assertThat(humanRequest.getStatus()).isEqualTo(HumanRequestStatus.PENDING);
        assertThat(humanRequest.getSourceStepId()).isEqualTo(step.getStepId());
        assertThat(humanRequest.getSourceTrigger()).isEqualTo(ReplanningTrigger.EXECUTION_READINESS_MISSING.name());
        assertThat(humanRequest.getPlannerDecisionId()).isNotBlank();
        assertThat(humanRequest.getPolicyReason()).isEqualTo(PolicyValidationReasonCode.HUMAN_INPUT_REQUIRED.name());
        assertThat(humanRequest.getWaitingReason()).contains("Missing target environment");
        assertThat(humanRequest.getRequiredInputSchema())
            .extracting(field -> field.get("name"))
            .containsExactly("targetEnvironment");
        assertThat(humanRequest.getMetadata())
            .containsEntry("sourceTrigger", ReplanningTrigger.EXECUTION_READINESS_MISSING.name())
            .containsEntry("sourceStepId", step.getStepId())
            .containsEntry("requestType", HumanRequestType.MISSING_INPUT.name());
        assertThat(metadataList(humanRequest.getMetadata().get("blockers"))).containsExactly("Missing targetEnvironment");
        assertThat(humanInTheLoop.activeRequestForTask(task.getTaskId()))
            .hasValueSatisfying(active -> assertThat(active.getRequestId()).isEqualTo(humanRequest.getRequestId()));
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
    }

    @Test
    void reviewPendingCreatesDraftReviewHumanRequestWithPendingDraftContext() {
        var task = saveTask(
            "task-hitl-replan-draft-review",
            TaskStatus.WAITING_FOR_REVIEW,
            PromotionMode.MANUAL,
            Map.of("lastReplanning", Map.of("resumeTaskStatus", TaskStatus.CASE_GENERATED.name()))
        );
        var step = saveStep(task.getTaskId(), "step-hitl-draft-review", PlanStepType.EXECUTE_BATCH, PlanStepStatus.PENDING, 20);
        drafts.save(draft(task.getTaskId(), "draft-hitl-review-1"));

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.REVIEW_COMPLETED,
            step.getStepId(),
            null,
            Map.of(),
            true,
            AgentPolicy.v2Phase2Default(),
            ContextBundleSummary.empty(),
            List.of()
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ReplanningStatus.WAITING_FOR_HUMAN);
        var humanRequest = humanInTheLoop.activeRequestForTask(task.getTaskId()).orElseThrow();
        assertThat(humanRequest.getRequestType()).isEqualTo(HumanRequestType.DRAFT_REVIEW);
        assertThat(humanRequest.getWaitingReason()).contains("manual review");
        assertThat(humanRequest.getSourceTrigger()).isEqualTo(ReplanningTrigger.REVIEW_COMPLETED.name());
        assertThat(humanRequest.getRequiredInputSchema())
            .extracting(field -> field.get("name"))
            .containsExactly("reviewDecision", "draftIds");
        assertThat(metadataList(humanRequest.getMetadata().get("pendingDraftIds")))
            .containsExactly("draft-hitl-review-1");
        assertThat(result.planMutationSummary()).containsEntry("humanReviewRequestId", humanRequest.getRequestId());
    }

    private ReplanningRequest waitForHumanRequest(String taskId, String stepId) {
        return new ReplanningRequest(
            taskId,
            ReplanningTrigger.EXECUTION_READINESS_MISSING,
            stepId,
            StepOutcome.blocked("Execution readiness is incomplete", List.of("Missing targetEnvironment")),
            Map.of(),
            false,
            AgentPolicy.v2Phase2Default()
                .withTaskPhase(AgentTaskPhase.EXECUTION)
                .withWorkflowMode(AgentWorkflowMode.AUTOMATIC),
            ContextBundleSummary.of("Approved case exists but environment is not configured", List.of("case:approved"), 0, 1, 200),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.WAIT_FOR_HUMAN.name()))
        );
    }

    private Task saveTask(String taskId, TaskStatus status, PromotionMode promotionMode, Map<String, Object> metadata) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("HITL replanning " + taskId);
        task.setStatus(status);
        task.setSourceType(TaskSourceType.OPENAPI);
        task.setSourceRef("source-" + taskId);
        task.setTargetApiSpecIds(List.of("api-" + taskId));
        task.setPromotionMode(promotionMode);
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

    private TestCaseDraft draft(String taskId, String draftId) {
        var draft = new TestCaseDraft();
        draft.setDraftId(draftId);
        draft.setTaskId(taskId);
        draft.setSource(CaseSource.STRUCTURE);
        draft.setStage(CaseSource.STRUCTURE);
        draft.setStatus(DraftStatus.PENDING_REVIEW);
        draft.setPromotionMode(PromotionMode.MANUAL);
        draft.setTargetApiSpecId("api-" + taskId);
        draft.setDedupKey("dedup-" + draftId);
        draft.setExpectedStatusCode(200);
        draft.setDraftContent(Map.of("title", draftId));
        return draft;
    }

    private List<String> metadataList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }
}
