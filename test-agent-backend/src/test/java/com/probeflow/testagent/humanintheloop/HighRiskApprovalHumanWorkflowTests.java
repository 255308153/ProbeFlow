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
import java.util.LinkedHashMap;
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
class HighRiskApprovalHumanWorkflowTests {

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
    void highRiskPolicyGateCreatesApprovalRequestWithPlannerPolicyAndStepContext() {
        var task = saveTask("task-hitl-high-risk-create", TaskStatus.ANALYZING_RESULTS, Map.of());
        var failed = saveStep(task.getTaskId(), "step-hitl-high-risk-failed", PlanStepType.ANALYZE_FAILURE, PlanStepStatus.FAILED, 10);
        var pending = saveStep(task.getTaskId(), "step-hitl-high-risk-pending", PlanStepType.GENERATE_REPORT, PlanStepStatus.PENDING, 20);

        var result = replanning.replan(highRiskReplanningRequest(task.getTaskId(), failed.getStepId()));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(ReplanningStatus.WAITING_FOR_HUMAN);
        assertThat(result.blockers()).containsExactly("HIGH_RISK_DECISION");
        assertThat(result.insertedStepIds()).isEmpty();
        assertThat(result.skippedStepIds()).isEmpty();

        var requests = humanInTheLoop.requestsForTask(task.getTaskId());
        assertThat(requests).hasSize(1);
        var request = requests.getFirst();
        assertThat(request.getRequestType()).isEqualTo(HumanRequestType.HIGH_RISK_APPROVAL);
        assertThat(request.getStatus()).isEqualTo(HumanRequestStatus.PENDING);
        assertThat(request.getRiskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(request.getSourceTrigger()).isEqualTo(ReplanningTrigger.FAILURE_ANALYSIS_HIGH_RISK.name());
        assertThat(request.getSourceStepId()).isEqualTo(failed.getStepId());
        assertThat(request.getPlannerDecisionId()).isNotBlank();
        assertThat(request.getPolicyReason()).isEqualTo(PolicyValidationReasonCode.HIGH_RISK_REQUIRES_CONFIRMATION.name());
        assertThat(request.getRequiredInputSchema())
            .extracting(field -> field.get("name"))
            .containsExactly("approved");
        assertThat(request.getMetadata())
            .containsEntry("plannerAction", "REPLAN")
            .containsEntry("policyReason", PolicyValidationReasonCode.HIGH_RISK_REQUIRES_CONFIRMATION.name());
        assertThat(metadataMap(request.getMetadata().get("sourceStepSummary")))
            .containsEntry("stepId", failed.getStepId())
            .containsEntry("stepStatus", PlanStepStatus.FAILED.name())
            .containsEntry("stepType", PlanStepType.ANALYZE_FAILURE.name());
        assertThat(metadataList(request.getMetadata().get("blockers")))
            .containsExactly("Human confirmation required before high-risk replan");

        var pausedTask = tasks.findById(task.getTaskId()).orElseThrow();
        assertThat(pausedTask.getStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
        assertThat(pausedTask.getMetadata()).containsKey("requiredHumanInput");
        assertStepUnchanged(failed.getStepId(), PlanStepStatus.FAILED, 10);
        assertStepUnchanged(pending.getStepId(), PlanStepStatus.PENDING, 20);
    }

    @Test
    void approveHighRiskDecisionWritesRecoverableContextMasksAuditAndResumesTask() {
        var task = saveTask(
            "task-hitl-high-risk-approve",
            TaskStatus.WAITING_FOR_REVIEW,
            Map.of(
                "requiredHumanInput", Map.of("question", "Approve high-risk recovery?"),
                "lastReplanning", Map.of("resumeTaskStatus", TaskStatus.ANALYZING_RESULTS.name())
            )
        );
        var request = createHighRiskRequest(task.getTaskId(), "step-hitl-high-risk-approve");

        var result = humanInTheLoop.applyHighRiskDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.APPROVE,
            "qa-owner",
            "Approved for the isolated staging environment.",
            Map.of(
                "approved", true,
                "approvalNote", "Run only against staging.",
                "token", "super-secret-token"
            )
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(HumanHighRiskDecisionStatus.APPROVED);
        assertThat(result.request().getStatus()).isEqualTo(HumanRequestStatus.CONSUMED);
        assertThat(humanRequests.findById(request.getRequestId()).orElseThrow().getStatus()).isEqualTo(HumanRequestStatus.CONSUMED);
        assertThat(metadataMap(result.auditSummary().get("payloadSummary")))
            .containsEntry("approved", true)
            .containsEntry("token", HumanInTheLoopApplicationService.MASKED_VALUE);

        var savedDecision = humanInTheLoop.decisionsForTask(task.getTaskId()).getFirst();
        assertThat(savedDecision.getPayload()).containsEntry("token", "super-secret-token");
        assertThat(savedDecision.getSanitizedPayloadSummary())
            .containsEntry("token", HumanInTheLoopApplicationService.MASKED_VALUE);

        var savedTask = tasks.findById(task.getTaskId()).orElseThrow();
        assertThat(savedTask.getStatus()).isEqualTo(TaskStatus.ANALYZING_RESULTS);
        assertThat(savedTask.getMetadata()).doesNotContainKey("requiredHumanInput");
        assertThat(metadataMap(savedTask.getMetadata().get("highRiskApprovalContext")))
            .containsEntry("requestId", request.getRequestId())
            .containsEntry("decisionType", HumanDecisionType.APPROVE.name())
            .containsEntry("approved", true)
            .containsEntry("policyReason", PolicyValidationReasonCode.HIGH_RISK_REQUIRES_CONFIRMATION.name());
        assertThat(metadataMap(savedTask.getMetadata().get("lastHighRiskDecision")))
            .containsEntry("approved", true)
            .containsEntry("riskLevel", ToolRiskLevel.HIGH.name());
    }

    @Test
    void rejectHighRiskDecisionSafelyStopsTaskAndPersistsReasonForFuturePlanningContext() {
        var task = saveTask(
            "task-hitl-high-risk-reject",
            TaskStatus.WAITING_FOR_REVIEW,
            Map.of(
                "requiredHumanInput", Map.of("question", "Approve high-risk recovery?"),
                "lastReplanning", Map.of("resumeTaskStatus", TaskStatus.ANALYZING_RESULTS.name())
            )
        );
        var request = createHighRiskRequest(task.getTaskId(), "step-hitl-high-risk-reject");

        var result = humanInTheLoop.applyHighRiskDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.REJECT,
            "security-owner",
            "Production-like data may be touched; require a safer plan.",
            Map.of("approved", false, "rejectionCategory", "unsafe-target", "apiToken", "token-to-mask")
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(HumanHighRiskDecisionStatus.DENIED);
        assertThat(result.request().getStatus()).isEqualTo(HumanRequestStatus.REJECTED);
        assertThat(humanRequests.findById(request.getRequestId()).orElseThrow().getStatus()).isEqualTo(HumanRequestStatus.REJECTED);
        assertThat(metadataMap(result.auditSummary().get("payloadSummary")))
            .containsEntry("apiToken", HumanInTheLoopApplicationService.MASKED_VALUE);

        var savedTask = tasks.findById(task.getTaskId()).orElseThrow();
        assertThat(savedTask.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(savedTask.getMetadata()).doesNotContainKey("requiredHumanInput");
        assertThat(metadataMap(savedTask.getMetadata().get("highRiskRejectionContext")))
            .containsEntry("requestId", request.getRequestId())
            .containsEntry("decisionType", HumanDecisionType.REJECT.name())
            .containsEntry("approved", false)
            .containsEntry("reason", "Production-like data may be touched; require a safer plan.");
        assertThat(metadataMap(savedTask.getMetadata().get("lastHighRiskDecision")))
            .containsEntry("approved", false)
            .containsEntry("policyReason", PolicyValidationReasonCode.HIGH_RISK_REQUIRES_CONFIRMATION.name());
    }

    @Test
    void rejectedHighRiskRequestCannotBeApprovedByDuplicateSubmission() {
        var task = saveTask("task-hitl-high-risk-stale", TaskStatus.WAITING_FOR_REVIEW, Map.of());
        var request = createHighRiskRequest(task.getTaskId(), "step-hitl-high-risk-stale");

        var rejection = humanInTheLoop.applyHighRiskDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.REJECT,
            "security-owner",
            "Risk exceeds this task boundary.",
            Map.of("approved", false)
        ));
        var staleApproval = humanInTheLoop.applyHighRiskDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.APPROVE,
            "qa-owner",
            "Trying again should not reopen a rejected request.",
            Map.of("approved", true)
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(rejection.status()).isEqualTo(HumanHighRiskDecisionStatus.DENIED);
        assertThat(staleApproval.status()).isEqualTo(HumanHighRiskDecisionStatus.REJECTED);
        assertThat(staleApproval.blockers()).containsExactly("Human request is not pending: REJECTED");
        assertThat(humanInTheLoop.decisionsForTask(task.getTaskId())).hasSize(1);
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void approveRequiresExplicitApprovedTrueBeforeTaskCanResume() {
        var task = saveTask("task-hitl-high-risk-approved-flag", TaskStatus.WAITING_FOR_REVIEW, Map.of());
        var request = createHighRiskRequest(task.getTaskId(), "step-hitl-high-risk-approved-flag");

        var result = humanInTheLoop.applyHighRiskDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.APPROVE,
            "qa-owner",
            "This payload is not an approval signal.",
            Map.of("approved", false)
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(HumanHighRiskDecisionStatus.REJECTED);
        assertThat(result.blockers()).containsExactly("High-risk approval requires approved=true");
        assertThat(humanRequests.findById(request.getRequestId()).orElseThrow().getStatus()).isEqualTo(HumanRequestStatus.PENDING);
        assertThat(humanInTheLoop.decisionsForTask(task.getTaskId())).isEmpty();
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
    }

    private ReplanningRequest highRiskReplanningRequest(String taskId, String sourceStepId) {
        return new ReplanningRequest(
            taskId,
            ReplanningTrigger.FAILURE_ANALYSIS_HIGH_RISK,
            sourceStepId,
            new StepOutcome(
                PlanStepStatus.FAILED,
                TaskStatus.FAILED,
                "Failure analysis found a risky recovery path",
                List.of("executionRecord:exec-" + taskId),
                null,
                List.of("Human confirmation required before high-risk replan"),
                true
            ),
            Map.of(),
            false,
            AgentPolicy.v2Phase2Default()
                .withTaskPhase(AgentTaskPhase.FAILURE_ANALYSIS)
                .withWorkflowMode(AgentWorkflowMode.AUTOMATIC),
            ContextBundleSummary.of("High-risk failure analysis context", List.of("exec:" + taskId), 0, 0, 200),
            List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, FakePlannerScenario.HIGH_RISK_REPLAN.name()))
        );
    }

    private HumanReviewRequest createHighRiskRequest(String taskId, String sourceStepId) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("blockers", List.of("HIGH_RISK_DECISION"));
        metadata.put("sourceStepSummary", Map.of("stepId", sourceStepId, "stepStatus", PlanStepStatus.FAILED.name()));
        metadata.put("plannerAction", "REPLAN");
        metadata.put("plannerInputTraceId", "trace-" + taskId);
        return humanInTheLoop.createRequest(new HumanReviewRequestCreateRequest(
            taskId,
            sourceStepId,
            HumanRequestType.HIGH_RISK_APPROVAL,
            "Planner decision is high risk and requires human confirmation.",
            List.of(
                Map.of("name", "approved", "type", "boolean", "required", true),
                Map.of("name", "approvalNote", "type", "string", "required", false),
                Map.of("name", "rejectionCategory", "type", "string", "required", false),
                Map.of("name", "token", "type", "string", "required", false),
                Map.of("name", "apiToken", "type", "string", "required", false)
            ),
            ToolRiskLevel.HIGH,
            ReplanningTrigger.FAILURE_ANALYSIS_HIGH_RISK.name(),
            "decision-high-risk-" + taskId,
            PolicyValidationReasonCode.HIGH_RISK_REQUIRES_CONFIRMATION.name(),
            metadata,
            null
        )).request();
    }

    private Task saveTask(String taskId, TaskStatus status, Map<String, Object> metadata) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("High-risk approval " + taskId);
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

    private void assertStepUnchanged(String stepId, PlanStepStatus status, int order) {
        var step = planSteps.findById(stepId).orElseThrow();
        assertThat(step.getStepStatus()).isEqualTo(status);
        assertThat(step.getStepOrder()).isEqualTo(order);
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
