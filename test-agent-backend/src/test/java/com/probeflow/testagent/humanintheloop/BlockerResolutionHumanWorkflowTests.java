package com.probeflow.testagent.humanintheloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.AgentWorkflowMode;
import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
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
class BlockerResolutionHumanWorkflowTests {

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
    void readinessMissingCreatesMissingInputRequestWithSchemaAndDeduplicatesPendingBlocker() {
        var task = saveTask("task-hitl-blocker-readiness", TaskStatus.EXECUTING, Map.of());
        var step = saveStep(task.getTaskId(), "step-hitl-blocker-readiness", PlanStepType.EXECUTE_BATCH, PlanStepStatus.PENDING, 10);

        var first = replanning.replan(waitForHumanRequest(task.getTaskId(), step.getStepId()));
        var second = replanning.replan(waitForHumanRequest(task.getTaskId(), step.getStepId()));

        entityManager.flush();
        entityManager.clear();

        assertThat(first.status()).isEqualTo(ReplanningStatus.WAITING_FOR_HUMAN);
        assertThat(second.status()).isIn(ReplanningStatus.WAITING_FOR_HUMAN, ReplanningStatus.NOOP);
        var requests = humanInTheLoop.requestsForTask(task.getTaskId());
        assertThat(requests).hasSize(1);
        var request = requests.getFirst();
        assertThat(request.getRequestType()).isEqualTo(HumanRequestType.MISSING_INPUT);
        assertThat(request.getWaitingReason()).contains("Missing target environment");
        assertThat(request.getRequiredInputSchema())
            .extracting(field -> field.get("name"))
            .containsExactly("targetEnvironment");
        assertThat(metadataList(request.getMetadata().get("blockers"))).containsExactly("Missing targetEnvironment");
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
    }

    @Test
    void provideInputDecisionStoresRecoverableContextMasksAuditAndResumesTask() {
        var task = saveTask(
            "task-hitl-blocker-provide",
            TaskStatus.WAITING_FOR_REVIEW,
            Map.of(
                "requiredHumanInput", Map.of("question", "Which environment and base URL should execution use?"),
                "lastReplanning", Map.of("resumeTaskStatus", TaskStatus.EXECUTING.name())
            )
        );
        var request = createInputRequest(task.getTaskId(), HumanRequestType.MISSING_INPUT, ReplanningTrigger.EXECUTION_READINESS_MISSING);

        var result = humanInTheLoop.applyBlockerResolutionDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.PROVIDE_INPUT,
            "release-owner",
            "Use staging credentials for this run.",
            Map.of(
                "baseUrl", "https://staging.example.test",
                "environment", "staging",
                "token", "secret-token-123",
                "credentialRef", "vault://qa/staging-token"
            )
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(HumanInputResolutionStatus.APPLIED);
        assertThat(result.request().getStatus()).isEqualTo(HumanRequestStatus.CONSUMED);
        assertThat(humanRequests.findById(request.getRequestId()).orElseThrow().getStatus()).isEqualTo(HumanRequestStatus.CONSUMED);
        var savedDecision = humanInTheLoop.decisionsForTask(task.getTaskId()).getFirst();
        assertThat(savedDecision.getPayload()).containsEntry("token", "secret-token-123");
        assertThat(savedDecision.getSanitizedPayloadSummary())
            .containsEntry("baseUrl", "https://staging.example.test")
            .containsEntry("token", HumanInTheLoopApplicationService.MASKED_VALUE);
        assertThat(metadataMap(result.auditSummary().get("payloadSummary")))
            .containsEntry("token", HumanInTheLoopApplicationService.MASKED_VALUE);

        var savedTask = tasks.findById(task.getTaskId()).orElseThrow();
        assertThat(savedTask.getStatus()).isEqualTo(TaskStatus.EXECUTING);
        assertThat(savedTask.getMetadata()).doesNotContainKey("requiredHumanInput");
        assertThat(metadataMap(savedTask.getMetadata().get("humanInputContext")))
            .containsEntry("baseUrl", "https://staging.example.test")
            .containsEntry("environment", "staging")
            .containsEntry("token", "secret-token-123");
        assertThat(metadataMap(savedTask.getMetadata().get("humanInputContextSummary")))
            .containsEntry("token", HumanInTheLoopApplicationService.MASKED_VALUE)
            .containsEntry("credentialRef", "vault://qa/staging-token");
        assertThat(metadataMap(savedTask.getMetadata().get("lastHumanInputResolution")))
            .containsEntry("requestId", request.getRequestId())
            .containsEntry("decisionType", HumanDecisionType.PROVIDE_INPUT.name());
    }

    @Test
    void missingRequiredInputIsRejectedAndTaskRemainsWaiting() {
        var task = saveTask(
            "task-hitl-blocker-missing",
            TaskStatus.WAITING_FOR_REVIEW,
            Map.of("requiredHumanInput", Map.of("question", "Need base URL"))
        );
        var request = createInputRequest(task.getTaskId(), HumanRequestType.MISSING_INPUT, ReplanningTrigger.EXECUTION_READINESS_MISSING);

        var result = humanInTheLoop.applyBlockerResolutionDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.PROVIDE_INPUT,
            "release-owner",
            "Still missing base URL.",
            Map.of("environment", "staging")
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(HumanInputResolutionStatus.REJECTED);
        assertThat(result.blockers()).containsExactly("Missing required human input: baseUrl");
        assertThat(humanRequests.findById(request.getRequestId()).orElseThrow().getStatus()).isEqualTo(HumanRequestStatus.PENDING);
        assertThat(humanInTheLoop.decisionsForTask(task.getTaskId())).isEmpty();
        var savedTask = tasks.findById(task.getTaskId()).orElseThrow();
        assertThat(savedTask.getStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
        assertThat(savedTask.getMetadata()).containsKey("requiredHumanInput");
    }

    @Test
    void resolveBlockerDecisionSupportsContextMissingRequests() {
        var task = saveTask(
            "task-hitl-blocker-context",
            TaskStatus.WAITING_FOR_REVIEW,
            Map.of("lastReplanning", Map.of("resumeTaskStatus", TaskStatus.ANALYZING.name()))
        );
        var request = createInputRequest(task.getTaskId(), HumanRequestType.BLOCKER_RESOLUTION, ReplanningTrigger.CONTEXT_MISSING);

        var result = humanInTheLoop.applyBlockerResolutionDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.RESOLVE_BLOCKER,
            "api-owner",
            "Endpoint contract is documented in the linked runbook.",
            Map.of(
                "baseUrl", "https://docs.example.test/orders",
                "environment", "docs",
                "credentialRef", "not-required"
            )
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(HumanInputResolutionStatus.APPLIED);
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.ANALYZING);
        assertThat(metadataMap(tasks.findById(task.getTaskId()).orElseThrow().getMetadata().get("lastHumanInputResolution")))
            .containsEntry("requestType", HumanRequestType.BLOCKER_RESOLUTION.name())
            .containsEntry("sourceTrigger", ReplanningTrigger.CONTEXT_MISSING.name());
    }

    @Test
    void completedOrCancelledTasksRejectBlockerResolutionWithoutConsumingRequest() {
        var completed = saveTask("task-hitl-blocker-completed", TaskStatus.WAITING_FOR_REVIEW, Map.of());
        var completedRequest = createInputRequest(completed.getTaskId(), HumanRequestType.MISSING_INPUT, ReplanningTrigger.EXECUTION_READINESS_MISSING);
        completed.setStatus(TaskStatus.COMPLETED);
        tasks.save(completed);

        var cancelled = saveTask("task-hitl-blocker-cancelled", TaskStatus.WAITING_FOR_REVIEW, Map.of());
        var cancelledRequest = createInputRequest(cancelled.getTaskId(), HumanRequestType.BLOCKER_RESOLUTION, ReplanningTrigger.CONTEXT_MISSING);
        cancelled.setStatus(TaskStatus.CANCELLED);
        tasks.save(cancelled);

        var completedResult = humanInTheLoop.applyBlockerResolutionDecision(validResolution(completedRequest.getRequestId()));
        var cancelledResult = humanInTheLoop.applyBlockerResolutionDecision(validResolution(cancelledRequest.getRequestId()));

        entityManager.flush();
        entityManager.clear();

        assertThat(completedResult.status()).isEqualTo(HumanInputResolutionStatus.REJECTED);
        assertThat(completedResult.blockers()).containsExactly("Completed task cannot consume blocker resolution decision");
        assertThat(cancelledResult.status()).isEqualTo(HumanInputResolutionStatus.REJECTED);
        assertThat(cancelledResult.blockers()).containsExactly("Cancelled task cannot consume blocker resolution decision");
        assertThat(humanRequests.findById(completedRequest.getRequestId()).orElseThrow().getStatus()).isEqualTo(HumanRequestStatus.PENDING);
        assertThat(humanRequests.findById(cancelledRequest.getRequestId()).orElseThrow().getStatus()).isEqualTo(HumanRequestStatus.PENDING);
        assertThat(humanInTheLoop.decisionsForTask(completed.getTaskId())).isEmpty();
        assertThat(humanInTheLoop.decisionsForTask(cancelled.getTaskId())).isEmpty();
    }

    private HumanDecisionSubmissionRequest validResolution(String requestId) {
        return new HumanDecisionSubmissionRequest(
            requestId,
            HumanDecisionType.PROVIDE_INPUT,
            "release-owner",
            "Ready.",
            Map.of("baseUrl", "https://staging.example.test", "environment", "staging")
        );
    }

    private HumanReviewRequest createInputRequest(
        String taskId,
        HumanRequestType requestType,
        ReplanningTrigger trigger
    ) {
        return humanInTheLoop.createRequest(new HumanReviewRequestCreateRequest(
            taskId,
            "step-human-input",
            requestType,
            "Missing execution input: baseUrl, environment and credential reference.",
            List.of(
                Map.of("name", "baseUrl", "type", "string", "required", true),
                Map.of("name", "environment", "type", "string", "required", true),
                Map.of("name", "token", "type", "string", "required", false),
                Map.of("name", "credentialRef", "type", "string", "required", false)
            ),
            ToolRiskLevel.MEDIUM,
            trigger.name(),
            "decision-input-" + taskId,
            PolicyValidationReasonCode.HUMAN_INPUT_REQUIRED.name(),
            Map.of("blockers", List.of("Missing baseUrl", "Missing environment")),
            null
        )).request();
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

    private Task saveTask(String taskId, TaskStatus status, Map<String, Object> metadata) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Blocker resolution " + taskId);
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
