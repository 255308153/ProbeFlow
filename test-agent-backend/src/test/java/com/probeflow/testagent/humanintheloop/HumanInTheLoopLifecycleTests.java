package com.probeflow.testagent.humanintheloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
class HumanInTheLoopLifecycleTests {

    @Autowired
    private HumanInTheLoopApplicationService humanInTheLoop;

    @Autowired
    private HumanReviewRequestRepository humanRequests;

    @Autowired
    private HumanDecisionRecordRepository decisions;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private EntityManager entityManager;

    @Test
    void requestAndDecisionContractsExposePhase6LifecycleVocabulary() {
        assertThat(HumanRequestType.values()).containsExactly(
            HumanRequestType.DRAFT_REVIEW,
            HumanRequestType.BLOCKER_RESOLUTION,
            HumanRequestType.HIGH_RISK_APPROVAL,
            HumanRequestType.PLANNER_CLARIFICATION,
            HumanRequestType.MISSING_INPUT,
            HumanRequestType.REVIEW_COMPLETION
        );
        assertThat(HumanRequestStatus.values()).containsExactly(
            HumanRequestStatus.PENDING,
            HumanRequestStatus.ANSWERED,
            HumanRequestStatus.CONSUMED,
            HumanRequestStatus.REJECTED,
            HumanRequestStatus.CANCELLED,
            HumanRequestStatus.EXPIRED
        );
        assertThat(HumanDecisionType.values()).containsExactly(
            HumanDecisionType.APPROVE,
            HumanDecisionType.REJECT,
            HumanDecisionType.PROVIDE_INPUT,
            HumanDecisionType.REQUEST_CHANGES,
            HumanDecisionType.PROMOTE_DRAFT,
            HumanDecisionType.DISCARD_DRAFT,
            HumanDecisionType.RESOLVE_BLOCKER
        );
    }

    @Test
    void createsPendingHumanRequestWithAuditableTaskStepPolicyAndSchemaContext() {
        var task = saveTask("task-hitl-create", TaskStatus.ANALYZING);

        var result = humanInTheLoop.createRequest(new HumanReviewRequestCreateRequest(
            task.getTaskId(),
            "step-generate",
            HumanRequestType.MISSING_INPUT,
            "Need staging base URL before execution can continue.",
            List.of(Map.of(
                "name", "baseUrl",
                "type", "string",
                "required", true
            )),
            ToolRiskLevel.MEDIUM,
            "EXECUTION_READINESS_MISSING",
            "decision-123",
            "HUMAN_INPUT_REQUIRED",
            Map.of("blocker", "missing-base-url"),
            Instant.parse("2030-01-01T00:00:00Z")
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(HumanReviewRequestCreationStatus.CREATED);
        assertThat(result.request()).isNotNull();
        var saved = humanRequests.findById(result.request().getRequestId()).orElseThrow();
        assertThat(saved.getTaskId()).isEqualTo(task.getTaskId());
        assertThat(saved.getSourceStepId()).isEqualTo("step-generate");
        assertThat(saved.getRequestType()).isEqualTo(HumanRequestType.MISSING_INPUT);
        assertThat(saved.getStatus()).isEqualTo(HumanRequestStatus.PENDING);
        assertThat(saved.getWaitingReason()).contains("staging base URL");
        assertThat(saved.getRequiredInputSchema()).containsExactly(Map.of(
            "name", "baseUrl",
            "type", "string",
            "required", true
        ));
        assertThat(saved.getRiskLevel()).isEqualTo(ToolRiskLevel.MEDIUM);
        assertThat(saved.getSourceTrigger()).isEqualTo("EXECUTION_READINESS_MISSING");
        assertThat(saved.getPlannerDecisionId()).isEqualTo("decision-123");
        assertThat(saved.getPolicyReason()).isEqualTo("HUMAN_INPUT_REQUIRED");
        assertThat(saved.getMetadata()).containsEntry("blocker", "missing-base-url");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getExpiresAt()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
    }

    @Test
    void requestQueriesSupportTaskPendingStatusAndTypeLookups() {
        var task = saveTask("task-hitl-query", TaskStatus.EXECUTING);
        var otherTask = saveTask("task-hitl-query-other", TaskStatus.ANALYZING);
        var blocker = createRequest(task.getTaskId(), HumanRequestType.BLOCKER_RESOLUTION, "missing token");
        var clarification = createRequest(task.getTaskId(), HumanRequestType.PLANNER_CLARIFICATION, "which env");
        createRequest(otherTask.getTaskId(), HumanRequestType.DRAFT_REVIEW, "review drafts");

        blocker.markAnswered();
        humanRequests.save(blocker);
        entityManager.flush();
        entityManager.clear();

        assertThat(humanInTheLoop.requestsForTask(task.getTaskId()))
            .extracting(HumanReviewRequest::getRequestType)
            .containsExactly(HumanRequestType.BLOCKER_RESOLUTION, HumanRequestType.PLANNER_CLARIFICATION);
        assertThat(humanInTheLoop.pendingRequests())
            .extracting(HumanReviewRequest::getRequestId)
            .contains(clarification.getRequestId())
            .doesNotContain(blocker.getRequestId());
        assertThat(humanInTheLoop.requestsByType(HumanRequestType.PLANNER_CLARIFICATION))
            .extracting(HumanReviewRequest::getRequestId)
            .containsExactly(clarification.getRequestId());
    }

    @Test
    void decisionRecordsPersistImmutableHumanAnswerContextAndAreQueryableByTask() {
        var task = saveTask("task-hitl-decision", TaskStatus.WAITING_FOR_REVIEW);
        var request = createRequest(task.getTaskId(), HumanRequestType.HIGH_RISK_APPROVAL, "approve execution");
        var decision = new HumanDecisionRecord();
        decision.setRequestId(request.getRequestId());
        decision.setTaskId(task.getTaskId());
        decision.setDecisionType(HumanDecisionType.REJECT);
        decision.setActor("qa-owner");
        decision.setReason("Do not run against production.");
        decision.setPayload(Map.of("approved", false, "environment", "prod"));
        decision.setSanitizedPayloadSummary(Map.of("approved", false, "environment", "prod"));
        decisions.save(decision);

        entityManager.flush();
        entityManager.clear();

        assertThat(humanInTheLoop.decisionsForTask(task.getTaskId()))
            .hasSize(1)
            .first()
            .satisfies(saved -> {
                assertThat(saved.getRequestId()).isEqualTo(request.getRequestId());
                assertThat(saved.getDecisionType()).isEqualTo(HumanDecisionType.REJECT);
                assertThat(saved.getActor()).isEqualTo("qa-owner");
                assertThat(saved.getReason()).contains("production");
                assertThat(saved.getPayload()).containsEntry("approved", false);
                assertThat(saved.getSanitizedPayloadSummary()).containsEntry("environment", "prod");
                assertThat(saved.getCreatedAt()).isNotNull();
            });
    }

    @Test
    void completedAndCancelledTasksRejectNewHumanRequests() {
        var completed = saveTask("task-hitl-completed", TaskStatus.COMPLETED);
        var cancelled = saveTask("task-hitl-cancelled", TaskStatus.CANCELLED);

        var completedResult = humanInTheLoop.createRequest(requestFor(completed.getTaskId(), HumanRequestType.REVIEW_COMPLETION));
        var cancelledResult = humanInTheLoop.createRequest(requestFor(cancelled.getTaskId(), HumanRequestType.MISSING_INPUT));

        assertThat(completedResult.status()).isEqualTo(HumanReviewRequestCreationStatus.REJECTED);
        assertThat(completedResult.blockers()).containsExactly("Completed task cannot accept new human review request");
        assertThat(cancelledResult.status()).isEqualTo(HumanReviewRequestCreationStatus.REJECTED);
        assertThat(cancelledResult.blockers()).containsExactly("Cancelled task cannot accept new human review request");
        assertThat(humanRequests.findByTaskIdOrderByCreatedAtAsc(completed.getTaskId())).isEmpty();
        assertThat(humanRequests.findByTaskIdOrderByCreatedAtAsc(cancelled.getTaskId())).isEmpty();
    }

    private HumanReviewRequest createRequest(String taskId, HumanRequestType type, String reason) {
        return humanInTheLoop.createRequest(requestFor(taskId, type, reason)).request();
    }

    private HumanReviewRequestCreateRequest requestFor(String taskId, HumanRequestType type) {
        return requestFor(taskId, type, "Need human input.");
    }

    private HumanReviewRequestCreateRequest requestFor(String taskId, HumanRequestType type, String reason) {
        return new HumanReviewRequestCreateRequest(
            taskId,
            null,
            type,
            reason,
            List.of(Map.of("name", "answer", "type", "string", "required", true)),
            ToolRiskLevel.LOW,
            "ISSUE_01_TEST",
            null,
            null,
            Map.of(),
            null
        );
    }

    private Task saveTask(String taskId, TaskStatus status) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("HITL " + taskId);
        task.setStatus(status);
        task.setSourceType(TaskSourceType.OPENAPI);
        task.setSourceRef("source-" + taskId);
        task.setTargetApiSpecIds(List.of("api-" + taskId));
        task.setPromotionMode(PromotionMode.AUTO);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase6");
        task.setMetadata(Map.of());
        return tasks.save(task);
    }
}
