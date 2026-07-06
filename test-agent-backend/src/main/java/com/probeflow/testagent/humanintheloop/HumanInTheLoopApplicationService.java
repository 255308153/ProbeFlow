package com.probeflow.testagent.humanintheloop;

import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HumanInTheLoopApplicationService {

    private final TaskRepository tasks;
    private final HumanReviewRequestRepository humanRequests;
    private final HumanDecisionRecordRepository decisions;

    public HumanInTheLoopApplicationService(
        TaskRepository tasks,
        HumanReviewRequestRepository humanRequests,
        HumanDecisionRecordRepository decisions
    ) {
        this.tasks = tasks;
        this.humanRequests = humanRequests;
        this.decisions = decisions;
    }

    @Transactional
    public HumanReviewRequestCreationResult createRequest(HumanReviewRequestCreateRequest request) {
        return createRequestInternal(request, null);
    }

    @Transactional
    public HumanReviewRequestCreationResult createOrReusePendingRequest(
        HumanReviewRequestCreateRequest request,
        String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return createRequestInternal(request, null);
        }
        var existing = humanRequests.findByTaskIdAndRequestTypeAndStatusOrderByCreatedAtAsc(
                request.taskId(),
                request.requestType(),
                HumanRequestStatus.PENDING
            )
            .stream()
            .filter(candidate -> idempotencyKey.equals(metadataString(candidate.getMetadata().get("idempotencyKey"))))
            .findFirst();
        if (existing.isPresent()) {
            return HumanReviewRequestCreationResult.existingPending(existing.get());
        }
        return createRequestInternal(request, idempotencyKey);
    }

    private HumanReviewRequestCreationResult createRequestInternal(
        HumanReviewRequestCreateRequest request,
        String idempotencyKey
    ) {
        var task = tasks.findById(request.taskId());
        if (task.isEmpty()) {
            return HumanReviewRequestCreationResult.rejected(List.of("Task not found: " + request.taskId()));
        }
        if (task.get().getStatus() == TaskStatus.COMPLETED) {
            return HumanReviewRequestCreationResult.rejected(List.of("Completed task cannot accept new human review request"));
        }
        if (task.get().getStatus() == TaskStatus.CANCELLED) {
            return HumanReviewRequestCreationResult.rejected(List.of("Cancelled task cannot accept new human review request"));
        }

        var humanRequest = new HumanReviewRequest();
        humanRequest.setTaskId(request.taskId());
        humanRequest.setSourceStepId(request.sourceStepId());
        humanRequest.setRequestType(request.requestType());
        humanRequest.setStatus(HumanRequestStatus.PENDING);
        humanRequest.setWaitingReason(request.waitingReason());
        humanRequest.setRequiredInputSchema(request.requiredInputSchema());
        humanRequest.setRiskLevel(request.riskLevel());
        humanRequest.setSourceTrigger(request.sourceTrigger());
        humanRequest.setPlannerDecisionId(request.plannerDecisionId());
        humanRequest.setPolicyReason(request.policyReason());
        humanRequest.setMetadata(metadataWithIdempotencyKey(request.metadata(), idempotencyKey));
        humanRequest.setExpiresAt(request.expiresAt());
        return HumanReviewRequestCreationResult.created(humanRequests.save(humanRequest));
    }

    @Transactional(readOnly = true)
    public List<HumanReviewRequest> requestsForTask(String taskId) {
        return humanRequests.findByTaskIdOrderByCreatedAtAsc(taskId);
    }

    @Transactional(readOnly = true)
    public List<HumanReviewRequest> pendingRequests() {
        return humanRequests.findByStatusOrderByCreatedAtAsc(HumanRequestStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<HumanReviewRequest> pendingRequestsForTask(String taskId) {
        return humanRequests.findByTaskIdAndStatusOrderByCreatedAtAsc(taskId, HumanRequestStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public Optional<HumanReviewRequest> activeRequestForTask(String taskId) {
        return pendingRequestsForTask(taskId).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<HumanReviewRequest> requestsByType(HumanRequestType requestType) {
        return humanRequests.findByRequestTypeOrderByCreatedAtAsc(requestType);
    }

    @Transactional(readOnly = true)
    public List<HumanDecisionRecord> decisionsForTask(String taskId) {
        return decisions.findByTaskIdOrderByCreatedAtAsc(taskId);
    }

    private Map<String, Object> metadataWithIdempotencyKey(Map<String, Object> metadata, String idempotencyKey) {
        var result = metadata == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(metadata);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            result.put("idempotencyKey", idempotencyKey);
        }
        return Map.copyOf(result);
    }

    private String metadataString(Object value) {
        return value == null ? "" : value.toString();
    }
}
