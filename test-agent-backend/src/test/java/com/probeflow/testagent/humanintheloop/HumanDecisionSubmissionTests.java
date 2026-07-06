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
class HumanDecisionSubmissionTests {

    @Autowired
    private HumanInTheLoopApplicationService humanInTheLoop;

    @Autowired
    private HumanReviewRequestRepository humanRequests;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private EntityManager entityManager;

    @Test
    void pendingRequestAcceptsValidDecisionRecordsAuditAndMasksSensitivePayloadSummary() {
        var task = saveTask("task-hitl-decision-submit", TaskStatus.WAITING_FOR_REVIEW);
        var request = createInputRequest(task.getTaskId());

        var result = humanInTheLoop.submitDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.PROVIDE_INPUT,
            "qa-owner",
            "Use staging only.",
            Map.of(
                "baseUrl", "https://staging.example.test",
                "token", "secret-token-123",
                "headers", Map.of("Authorization", "Bearer abc", "X-Trace", "trace-1"),
                "cookies", List.of("session=abc")
            )
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(HumanDecisionSubmissionStatus.ACCEPTED);
        assertThat(result.blockers()).isEmpty();
        assertThat(result.decision()).isNotNull();
        var savedRequest = humanRequests.findById(request.getRequestId()).orElseThrow();
        assertThat(savedRequest.getStatus()).isEqualTo(HumanRequestStatus.ANSWERED);
        assertThat(savedRequest.getAnsweredAt()).isNotNull();

        var savedDecision = humanInTheLoop.decisionsForTask(task.getTaskId()).getFirst();
        assertThat(savedDecision.getDecisionType()).isEqualTo(HumanDecisionType.PROVIDE_INPUT);
        assertThat(savedDecision.getActor()).isEqualTo("qa-owner");
        assertThat(savedDecision.getReason()).contains("staging");
        assertThat(savedDecision.getPayload()).containsEntry("token", "secret-token-123");
        assertThat(savedDecision.getSanitizedPayloadSummary())
            .containsEntry("baseUrl", "https://staging.example.test")
            .containsEntry("token", HumanInTheLoopApplicationService.MASKED_VALUE);
        assertThat(metadataMap(savedDecision.getSanitizedPayloadSummary().get("headers")))
            .containsEntry("Authorization", HumanInTheLoopApplicationService.MASKED_VALUE)
            .containsEntry("X-Trace", "trace-1");
        assertThat(result.auditSummary())
            .containsEntry("requestId", request.getRequestId())
            .containsEntry("decisionType", HumanDecisionType.PROVIDE_INPUT.name());
        assertThat(metadataMap(result.auditSummary().get("payloadSummary")))
            .containsEntry("token", HumanInTheLoopApplicationService.MASKED_VALUE);
    }

    @Test
    void invalidPayloadIsRejectedWithoutChangingRequestOrDecisionHistory() {
        var task = saveTask("task-hitl-decision-invalid", TaskStatus.WAITING_FOR_REVIEW);
        var request = createInputRequest(task.getTaskId());

        var result = humanInTheLoop.submitDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.PROVIDE_INPUT,
            "qa-owner",
            "Missing base URL.",
            Map.of("token", "secret-token-123")
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(HumanDecisionSubmissionStatus.REJECTED);
        assertThat(result.blockers()).containsExactly("Missing required human input: baseUrl");
        assertThat(humanRequests.findById(request.getRequestId()).orElseThrow().getStatus()).isEqualTo(HumanRequestStatus.PENDING);
        assertThat(humanInTheLoop.decisionsForTask(task.getTaskId())).isEmpty();
    }

    @Test
    void staleRequestsRejectDecisionSubmissionWithoutOverwritingExistingDecisionRecord() {
        var task = saveTask("task-hitl-decision-stale", TaskStatus.WAITING_FOR_REVIEW);
        var request = createInputRequest(task.getTaskId());
        var accepted = humanInTheLoop.submitDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.PROVIDE_INPUT,
            "qa-owner",
            "Initial answer.",
            Map.of("baseUrl", "https://staging.example.test")
        ));
        humanInTheLoop.consumeDecision(new HumanDecisionConsumptionRequest(request.getRequestId(), "replanner"));

        var duplicate = humanInTheLoop.submitDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.PROVIDE_INPUT,
            "qa-owner",
            "Attempt to overwrite.",
            Map.of("baseUrl", "https://prod.example.test")
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(accepted.status()).isEqualTo(HumanDecisionSubmissionStatus.ACCEPTED);
        assertThat(duplicate.status()).isEqualTo(HumanDecisionSubmissionStatus.REJECTED);
        assertThat(duplicate.blockers()).containsExactly("Human request is not pending: CONSUMED");
        assertThat(humanInTheLoop.decisionsForTask(task.getTaskId()))
            .hasSize(1)
            .first()
            .satisfies(saved -> assertThat(saved.getPayload()).containsEntry("baseUrl", "https://staging.example.test"));
    }

    @Test
    void decisionConsumptionIsIdempotentAndRejectsPendingOrCancelledRequests() {
        var task = saveTask("task-hitl-decision-consume", TaskStatus.WAITING_FOR_REVIEW);
        var answered = createInputRequest(task.getTaskId());
        humanInTheLoop.submitDecision(new HumanDecisionSubmissionRequest(
            answered.getRequestId(),
            HumanDecisionType.PROVIDE_INPUT,
            "qa-owner",
            "Ready.",
            Map.of("baseUrl", "https://staging.example.test")
        ));

        var first = humanInTheLoop.consumeDecision(new HumanDecisionConsumptionRequest(answered.getRequestId(), "replanner"));
        var second = humanInTheLoop.consumeDecision(new HumanDecisionConsumptionRequest(answered.getRequestId(), "replanner"));

        var pending = createInputRequest(task.getTaskId());
        var pendingResult = humanInTheLoop.consumeDecision(new HumanDecisionConsumptionRequest(pending.getRequestId(), "replanner"));
        pending.cancel();
        humanRequests.save(pending);
        var cancelledResult = humanInTheLoop.consumeDecision(new HumanDecisionConsumptionRequest(pending.getRequestId(), "replanner"));

        entityManager.flush();
        entityManager.clear();

        assertThat(first.status()).isEqualTo(HumanDecisionConsumptionStatus.CONSUMED);
        assertThat(second.status()).isEqualTo(HumanDecisionConsumptionStatus.ALREADY_CONSUMED);
        assertThat(pendingResult.status()).isEqualTo(HumanDecisionConsumptionStatus.REJECTED);
        assertThat(pendingResult.blockers()).containsExactly("Human request has not been answered yet");
        assertThat(cancelledResult.status()).isEqualTo(HumanDecisionConsumptionStatus.REJECTED);
        assertThat(cancelledResult.blockers()).containsExactly("Human request cannot be consumed: CANCELLED");
        assertThat(humanRequests.findById(answered.getRequestId()).orElseThrow().getStatus()).isEqualTo(HumanRequestStatus.CONSUMED);
    }

    @Test
    void blankActorIsRejectedWithoutDecisionRecord() {
        var task = saveTask("task-hitl-decision-actor", TaskStatus.WAITING_FOR_REVIEW);
        var request = createInputRequest(task.getTaskId());

        var result = humanInTheLoop.submitDecision(new HumanDecisionSubmissionRequest(
            request.getRequestId(),
            HumanDecisionType.PROVIDE_INPUT,
            " ",
            "Ready.",
            Map.of("baseUrl", "https://staging.example.test")
        ));

        assertThat(result.status()).isEqualTo(HumanDecisionSubmissionStatus.REJECTED);
        assertThat(result.blockers()).containsExactly("Decision actor is required");
        assertThat(humanInTheLoop.decisionsForTask(task.getTaskId())).isEmpty();
    }

    private HumanReviewRequest createInputRequest(String taskId) {
        return humanInTheLoop.createRequest(new HumanReviewRequestCreateRequest(
            taskId,
            "step-human-input",
            HumanRequestType.MISSING_INPUT,
            "Need a base URL before execution can continue.",
            List.of(
                Map.of("name", "baseUrl", "type", "string", "required", true),
                Map.of("name", "token", "type", "string", "required", false),
                Map.of("name", "headers", "type", "object", "required", false),
                Map.of("name", "cookies", "type", "array", "required", false)
            ),
            ToolRiskLevel.MEDIUM,
            "ISSUE_03_TEST",
            "decision-issue-03",
            "HUMAN_INPUT_REQUIRED",
            Map.of(),
            null
        )).request();
    }

    private Task saveTask(String taskId, TaskStatus status) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("HITL decision " + taskId);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataMap(Object value) {
        return (Map<String, Object>) value;
    }
}
