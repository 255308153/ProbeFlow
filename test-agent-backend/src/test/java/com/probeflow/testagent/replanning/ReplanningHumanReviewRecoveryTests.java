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
class ReplanningHumanReviewRecoveryTests {

    @Autowired
    private ReplanningApplicationService replanning;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private TestCaseDraftRepository drafts;

    @Autowired
    private EntityManager entityManager;

    @Test
    void readinessMissingTriggerWaitsForHumanAndRecordsRequiredInputWithoutChangingPlan() {
        var task = saveTask("task-replan-human-readiness", TaskStatus.EXECUTING, PromotionMode.AUTO, Map.of());
        var success = saveStep(task.getTaskId(), "step-human-readiness-success", PlanStepType.GENERATE_CASES, PlanStepStatus.SUCCESS, 10, "Generated cases", "drafts");
        var pending = saveStep(task.getTaskId(), "step-human-readiness-pending", PlanStepType.EXECUTE_BATCH, PlanStepStatus.PENDING, 20, "Execute batch", "exec-input");

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.EXECUTION_READINESS_MISSING,
            pending.getStepId(),
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
        assertThat(result.policySummary())
            .containsEntry("validationStatus", PolicyValidationStatus.REQUIRES_HUMAN_CONFIRMATION.name())
            .containsEntry("reasonCode", PolicyValidationReasonCode.HUMAN_INPUT_REQUIRED.name());
        assertThat(result.insertedStepIds()).isEmpty();
        assertThat(result.skippedStepIds()).isEmpty();

        var pausedTask = tasks.findById(task.getTaskId()).orElseThrow();
        assertThat(pausedTask.getStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
        var requiredInput = metadataMap(pausedTask.getMetadata().get("requiredHumanInput"));
        assertThat(requiredInput)
            .containsEntry("reason", "Missing target environment")
            .containsEntry("question", "Which configured environment should the next suggested step use?")
            .containsEntry("blocking", true);
        assertThat(inputSchema(requiredInput))
            .extracting(schema -> schema.get("name"))
            .containsExactly("targetEnvironment");
        assertThat(metadataMap(pausedTask.getMetadata().get("lastReplanning")))
            .containsEntry("resumeTaskStatus", TaskStatus.EXECUTING.name());

        assertStepUnchanged(success.getStepId(), PlanStepStatus.SUCCESS, 10, "Generated cases", "drafts");
        assertStepUnchanged(pending.getStepId(), PlanStepStatus.PENDING, 20, "Execute batch", "exec-input");
    }

    @Test
    void highRiskConfirmationWaitsForHumanWithoutSkippingOrInsertingSteps() {
        var task = saveTask("task-replan-human-high-risk", TaskStatus.ANALYZING_RESULTS, PromotionMode.AUTO, Map.of());
        var failed = saveStep(task.getTaskId(), "step-human-high-risk-failed", PlanStepType.ANALYZE_FAILURE, PlanStepStatus.FAILED, 10, "Analyze failure", "failure-input");
        var pending = saveStep(task.getTaskId(), "step-human-high-risk-pending", PlanStepType.GENERATE_REPORT, PlanStepStatus.PENDING, 20, "Generate report", "report-input");

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.FAILURE_ANALYSIS_HIGH_RISK,
            failed.getStepId(),
            new StepOutcome(
                PlanStepStatus.FAILED,
                TaskStatus.FAILED,
                "Failure analysis found a risky recovery path",
                List.of("executionRecord:exec-high-risk-human"),
                null,
                List.of("Human confirmation required before high-risk replan"),
                true
            ),
            Map.of(),
            false,
            AgentPolicy.v2Phase2Default()
                .withTaskPhase(AgentTaskPhase.FAILURE_ANALYSIS)
                .withWorkflowMode(AgentWorkflowMode.AUTOMATIC),
            ContextBundleSummary.of("High-risk failure analysis context", List.of("exec:exec-high-risk-human"), 0, 0, 200),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.HIGH_RISK_REPLAN.name()))
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ReplanningStatus.WAITING_FOR_HUMAN);
        assertThat(result.blockers()).containsExactly("HIGH_RISK_DECISION");
        assertThat(result.policySummary())
            .containsEntry("validationStatus", PolicyValidationStatus.REQUIRES_HUMAN_CONFIRMATION.name())
            .containsEntry("reasonCode", PolicyValidationReasonCode.HIGH_RISK_REQUIRES_CONFIRMATION.name());
        assertThat(result.insertedStepIds()).isEmpty();
        assertThat(result.skippedStepIds()).isEmpty();

        var pausedTask = tasks.findById(task.getTaskId()).orElseThrow();
        assertThat(pausedTask.getStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
        var requiredInput = metadataMap(pausedTask.getMetadata().get("requiredHumanInput"));
        assertThat(requiredInput)
            .containsEntry("policyReasonCode", PolicyValidationReasonCode.HIGH_RISK_REQUIRES_CONFIRMATION.name());
        assertThat((String) requiredInput.get("reason")).contains("high risk");
        assertThat(inputSchema(requiredInput).getFirst())
            .containsEntry("name", "approved")
            .containsEntry("type", "boolean")
            .containsEntry("required", true);
        assertThat(metadataMap(pausedTask.getMetadata().get("lastReplanning")))
            .containsEntry("resumeTaskStatus", TaskStatus.ANALYZING_RESULTS.name());

        assertStepUnchanged(failed.getStepId(), PlanStepStatus.FAILED, 10, "Analyze failure", "failure-input");
        assertStepUnchanged(pending.getStepId(), PlanStepStatus.PENDING, 20, "Generate report", "report-input");
    }

    @Test
    void reviewCompletedTriggerResumesPausedTaskAndKeepsTemplatePlan() {
        var task = saveTask(
            "task-replan-human-review-completed",
            TaskStatus.WAITING_FOR_REVIEW,
            PromotionMode.AUTO,
            Map.of(
                "requiredHumanInput", Map.of("question", "Which environment?"),
                "lastReplanning", Map.of("resumeTaskStatus", TaskStatus.EXECUTING.name())
            )
        );
        var success = saveStep(task.getTaskId(), "step-review-completed-success", PlanStepType.GENERATE_CASES, PlanStepStatus.SUCCESS, 10, "Generated cases", "drafts");
        var pending = saveStep(task.getTaskId(), "step-review-completed-pending", PlanStepType.EXECUTE_BATCH, PlanStepStatus.PENDING, 20, "Execute batch", "exec-input");

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.REVIEW_COMPLETED,
            pending.getStepId(),
            null,
            Map.of("targetEnvironment", "staging"),
            true,
            AgentPolicy.v2Phase2Default(),
            ContextBundleSummary.empty(),
            List.of()
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ReplanningStatus.APPLIED);
        assertThat(result.decisionSummary())
            .containsEntry("plannerCalled", false)
            .containsEntry("action", "CONTINUE");
        assertThat(result.planMutationSummary())
            .containsEntry("mutationApplied", true)
            .containsEntry("mutationType", "REVIEW_COMPLETED_RESUME")
            .containsEntry("taskStatus", TaskStatus.EXECUTING.name());
        assertThat(result.insertedStepIds()).isEmpty();
        assertThat(result.skippedStepIds()).isEmpty();

        var resumedTask = tasks.findById(task.getTaskId()).orElseThrow();
        assertThat(resumedTask.getStatus()).isEqualTo(TaskStatus.EXECUTING);
        assertThat(resumedTask.getMetadata()).doesNotContainKey("requiredHumanInput");
        assertThat(metadataMap(resumedTask.getMetadata().get("reviewCompletedHumanInput")))
            .containsEntry("targetEnvironment", "staging");
        assertThat(metadataMap(resumedTask.getMetadata().get("lastReplanning")))
            .containsEntry("trigger", ReplanningTrigger.REVIEW_COMPLETED.name())
            .containsEntry("reviewCompleted", true)
            .containsEntry("taskStatus", TaskStatus.EXECUTING.name());

        assertStepUnchanged(success.getStepId(), PlanStepStatus.SUCCESS, 10, "Generated cases", "drafts");
        assertStepUnchanged(pending.getStepId(), PlanStepStatus.PENDING, 20, "Execute batch", "exec-input");
    }

    @Test
    void reviewCompletedTriggerRemainsWaitingWhenManualReviewGateIsNotReady() {
        var task = saveTask(
            "task-replan-human-manual-review",
            TaskStatus.WAITING_FOR_REVIEW,
            PromotionMode.MANUAL,
            Map.of("lastReplanning", Map.of("resumeTaskStatus", TaskStatus.CASE_GENERATED.name()))
        );
        var success = saveStep(task.getTaskId(), "step-manual-review-success", PlanStepType.GENERATE_CASES, PlanStepStatus.SUCCESS, 10, "Generated cases", "drafts");
        var pending = saveStep(task.getTaskId(), "step-manual-review-pending", PlanStepType.EXECUTE_BATCH, PlanStepStatus.PENDING, 20, "Execute batch", "exec-input");
        drafts.save(draft(task.getTaskId(), "draft-manual-review-pending", DraftStatus.PENDING_REVIEW, null));

        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            ReplanningTrigger.REVIEW_COMPLETED,
            pending.getStepId(),
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
        assertThat(result.blockers()).containsExactly("Waiting for manual review of 1 draft(s)");
        assertThat(result.planMutationSummary())
            .containsEntry("mutationApplied", false)
            .containsEntry("taskStatus", TaskStatus.WAITING_FOR_REVIEW.name())
            .containsEntry("manualReviewGateReady", false);
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
        assertStepUnchanged(success.getStepId(), PlanStepStatus.SUCCESS, 10, "Generated cases", "drafts");
        assertStepUnchanged(pending.getStepId(), PlanStepStatus.PENDING, 20, "Execute batch", "exec-input");
    }

    private Task saveTask(String taskId, TaskStatus status, PromotionMode promotionMode, Map<String, Object> metadata) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Human review replanning " + taskId);
        task.setStatus(status);
        task.setSourceType(TaskSourceType.OPENAPI);
        task.setSourceRef("source-" + taskId);
        task.setTargetApiSpecIds(List.of("api-" + taskId));
        task.setPromotionMode(promotionMode);
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

    private TestCaseDraft draft(String taskId, String draftId, DraftStatus status, String promotedCaseId) {
        var draft = new TestCaseDraft();
        draft.setDraftId(draftId);
        draft.setTaskId(taskId);
        draft.setSource(CaseSource.STRUCTURE);
        draft.setStage(CaseSource.STRUCTURE);
        draft.setStatus(status);
        draft.setPromotionMode(PromotionMode.MANUAL);
        draft.setTargetApiSpecId("api-1");
        draft.setDedupKey("dedup-" + draftId);
        draft.setExpectedStatusCode(200);
        draft.setDraftContent(Map.of("title", draftId));
        draft.setPromotedCaseId(promotedCaseId);
        return draft;
    }

    private void assertStepUnchanged(String stepId, PlanStepStatus status, int order, String goal, String inputRef) {
        var step = planSteps.findById(stepId).orElseThrow();
        assertThat(step.getStepStatus()).isEqualTo(status);
        assertThat(step.getStepOrder()).isEqualTo(order);
        assertThat(step.getGoal()).isEqualTo(goal);
        assertThat(step.getInputRef()).isEqualTo(inputRef);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataMap(Object value) {
        return (Map<String, Object>) value;
    }

    private List<Map<String, Object>> inputSchema(Map<String, Object> requiredInput) {
        return ((List<?>) requiredInput.get("inputSchema")).stream()
            .map(this::metadataMap)
            .toList();
    }
}
