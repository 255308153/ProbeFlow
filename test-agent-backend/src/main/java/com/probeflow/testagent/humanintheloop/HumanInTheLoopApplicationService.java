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

    public static final String MASKED_VALUE = "***MASKED***";

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

    @Transactional
    public HumanDecisionSubmissionResult submitDecision(HumanDecisionSubmissionRequest request) {
        var baseAudit = new LinkedHashMap<String, Object>();
        baseAudit.put("requestId", request == null ? null : request.requestId());
        if (request == null || request.requestId().isBlank()) {
            return HumanDecisionSubmissionResult.rejected(List.of("Human request id is required"), baseAudit);
        }
        if (request.decisionType() == null) {
            return HumanDecisionSubmissionResult.rejected(List.of("Decision type is required"), baseAudit);
        }
        baseAudit.put("decisionType", request.decisionType().name());
        if (request.actor().isBlank()) {
            return HumanDecisionSubmissionResult.rejected(List.of("Decision actor is required"), baseAudit);
        }
        baseAudit.put("actor", request.actor());

        var humanRequest = humanRequests.findById(request.requestId());
        if (humanRequest.isEmpty()) {
            return HumanDecisionSubmissionResult.rejected(List.of("Human request not found: " + request.requestId()), baseAudit);
        }
        if (humanRequest.get().getStatus() != HumanRequestStatus.PENDING) {
            return HumanDecisionSubmissionResult.rejected(
                List.of("Human request is not pending: " + humanRequest.get().getStatus().name()),
                baseAudit
            );
        }

        var validationBlockers = validatePayload(humanRequest.get(), request.payload());
        if (!validationBlockers.isEmpty()) {
            return HumanDecisionSubmissionResult.rejected(validationBlockers, baseAudit);
        }

        var sanitizedPayload = sanitizeMap(request.payload());
        var decision = new HumanDecisionRecord();
        decision.setRequestId(humanRequest.get().getRequestId());
        decision.setTaskId(humanRequest.get().getTaskId());
        decision.setDecisionType(request.decisionType());
        decision.setActor(request.actor());
        decision.setReason(request.reason());
        decision.setPayload(request.payload());
        decision.setSanitizedPayloadSummary(sanitizedPayload);
        var savedDecision = decisions.save(decision);

        humanRequest.get().markAnswered();
        var savedRequest = humanRequests.save(humanRequest.get());

        var audit = new LinkedHashMap<>(baseAudit);
        audit.put("taskId", savedRequest.getTaskId());
        audit.put("requestType", savedRequest.getRequestType().name());
        audit.put("payloadSummary", sanitizedPayload);
        audit.put("reason", request.reason());
        return HumanDecisionSubmissionResult.accepted(savedDecision, savedRequest, audit);
    }

    @Transactional
    public HumanDecisionConsumptionResult consumeDecision(HumanDecisionConsumptionRequest request) {
        var audit = new LinkedHashMap<String, Object>();
        audit.put("requestId", request == null ? null : request.requestId());
        audit.put("consumer", request == null ? null : request.consumer());
        if (request == null || request.requestId().isBlank()) {
            return HumanDecisionConsumptionResult.rejected(List.of("Human request id is required"), audit);
        }
        var humanRequest = humanRequests.findById(request.requestId());
        if (humanRequest.isEmpty()) {
            return HumanDecisionConsumptionResult.rejected(List.of("Human request not found: " + request.requestId()), audit);
        }
        audit.put("taskId", humanRequest.get().getTaskId());
        audit.put("requestStatus", humanRequest.get().getStatus().name());
        if (humanRequest.get().getStatus() == HumanRequestStatus.CONSUMED) {
            audit.put("idempotent", true);
            return HumanDecisionConsumptionResult.alreadyConsumed(humanRequest.get(), audit);
        }
        if (humanRequest.get().getStatus() == HumanRequestStatus.PENDING) {
            return HumanDecisionConsumptionResult.rejected(List.of("Human request has not been answered yet"), audit);
        }
        if (humanRequest.get().getStatus() != HumanRequestStatus.ANSWERED) {
            return HumanDecisionConsumptionResult.rejected(
                List.of("Human request cannot be consumed: " + humanRequest.get().getStatus().name()),
                audit
            );
        }
        humanRequest.get().markConsumed();
        var saved = humanRequests.save(humanRequest.get());
        audit.put("requestStatus", saved.getStatus().name());
        audit.put("idempotent", false);
        return HumanDecisionConsumptionResult.consumed(saved, audit);
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

    private List<String> validatePayload(HumanReviewRequest request, Map<String, Object> payload) {
        var blockers = request.getRequiredInputSchema().stream()
            .flatMap(field -> validateField(field, payload).stream())
            .toList();
        return List.copyOf(blockers);
    }

    private List<String> validateField(Map<String, Object> field, Map<String, Object> payload) {
        var name = metadataString(field.get("name"));
        if (name.isBlank()) {
            return List.of();
        }
        var required = Boolean.TRUE.equals(field.get("required"));
        if (!payload.containsKey(name) || payload.get(name) == null || blankString(payload.get(name))) {
            return required ? List.of("Missing required human input: " + name) : List.of();
        }
        var type = metadataString(field.get("type")).toLowerCase();
        if (!type.isBlank() && !matchesType(payload.get(name), type)) {
            return List.of("Invalid human input type for " + name + ": expected " + type);
        }
        return List.of();
    }

    private boolean matchesType(Object value, String type) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            case "number", "integer" -> value instanceof Number;
            default -> true;
        };
    }

    private boolean blankString(Object value) {
        return value instanceof String string && string.isBlank();
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> payload) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : payload.entrySet()) {
            result.put(entry.getKey(), sanitizeValue(entry.getKey(), entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private Object sanitizeValue(String key, Object value) {
        if (sensitiveKey(key)) {
            return MASKED_VALUE;
        }
        if (value instanceof Map<?, ?> map) {
            var sanitized = new LinkedHashMap<String, Object>();
            map.forEach((nestedKey, nestedValue) -> {
                var stringKey = nestedKey == null ? "" : nestedKey.toString();
                sanitized.put(stringKey, sanitizeValue(stringKey, nestedValue));
            });
            return Map.copyOf(sanitized);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .map(item -> sanitizeValue("", item))
                .toList();
        }
        return value;
    }

    private boolean sensitiveKey(String key) {
        var normalized = key == null ? "" : key.toLowerCase();
        return normalized.contains("token")
            || normalized.contains("secret")
            || normalized.contains("cookie")
            || normalized.contains("authorization")
            || normalized.contains("password");
    }
}
