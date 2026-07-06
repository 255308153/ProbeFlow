package com.probeflow.testagent.humanintheloop;

import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.testcasegeneration.TestCasePromotionRequest;
import com.probeflow.testagent.testcasegeneration.TestCasePromotionService;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.TestCaseDraft;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final TestCaseDraftRepository drafts;
    private final TestCasePromotionService casePromotion;

    public HumanInTheLoopApplicationService(
        TaskRepository tasks,
        HumanReviewRequestRepository humanRequests,
        HumanDecisionRecordRepository decisions,
        TestCaseDraftRepository drafts,
        TestCasePromotionService casePromotion
    ) {
        this.tasks = tasks;
        this.humanRequests = humanRequests;
        this.decisions = decisions;
        this.drafts = drafts;
        this.casePromotion = casePromotion;
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
    public HumanReviewRequestCreationResult createDraftReviewRequest(HumanDraftReviewRequest request) {
        if (request == null || request.taskId().isBlank()) {
            return HumanReviewRequestCreationResult.rejected(List.of("Task id is required"));
        }
        var task = tasks.findById(request.taskId());
        if (task.isEmpty()) {
            return HumanReviewRequestCreationResult.rejected(List.of("Task not found: " + request.taskId()));
        }

        var pendingDraftIds = drafts.findByTaskIdAndStatusOrderByCreatedAtAscDraftIdAsc(
                request.taskId(),
                DraftStatus.PENDING_REVIEW
            )
            .stream()
            .map(TestCaseDraft::getDraftId)
            .toList();
        if (pendingDraftIds.isEmpty()) {
            return HumanReviewRequestCreationResult.rejected(List.of("No pending test case drafts require review"));
        }

        var waitingReason = request.waitingReason() == null
            ? "Waiting for manual review of " + pendingDraftIds.size() + " draft(s)"
            : request.waitingReason();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("requestType", HumanRequestType.DRAFT_REVIEW.name());
        metadata.put("source", "HumanInTheLoopApplicationService");
        metadata.put("pendingDraftIds", pendingDraftIds);
        metadata.put("waitingReason", waitingReason);
        metadata.put("manualReviewCompatible", true);
        var idempotencyKey = humanRequestIdempotencyKey(
            task.get().getTaskId(),
            HumanRequestType.DRAFT_REVIEW,
            "MANUAL_REVIEW_GATE",
            request.sourceStepId(),
            pendingDraftIds
        );

        return createOrReusePendingRequest(new HumanReviewRequestCreateRequest(
            task.get().getTaskId(),
            request.sourceStepId(),
            HumanRequestType.DRAFT_REVIEW,
            waitingReason,
            draftReviewSchema(),
            ToolRiskLevel.LOW,
            "MANUAL_REVIEW_GATE",
            null,
            "MANUAL_REVIEW_PENDING",
            metadata,
            null
        ), idempotencyKey);
    }

    @Transactional
    public HumanDraftReviewDecisionResult applyDraftReviewDecision(HumanDecisionSubmissionRequest request) {
        var audit = new LinkedHashMap<String, Object>();
        audit.put("requestId", request == null ? null : request.requestId());
        audit.put("decisionType", request == null || request.decisionType() == null ? null : request.decisionType().name());
        audit.put("actor", request == null ? null : request.actor());
        var humanRequest = request == null || request.requestId().isBlank()
            ? Optional.<HumanReviewRequest>empty()
            : humanRequests.findById(request.requestId());

        var validationBlockers = validateDraftReviewDecision(request, humanRequest);
        if (!validationBlockers.isEmpty()) {
            return HumanDraftReviewDecisionResult.rejected(validationBlockers, compact(audit));
        }

        var draftIds = draftIds(request.payload().get("draftIds"));
        var submission = submitDecision(request);
        if (submission.status() == HumanDecisionSubmissionStatus.REJECTED) {
            return HumanDraftReviewDecisionResult.rejected(submission.blockers(), submission.auditSummary());
        }

        var promotedCaseIds = new ArrayList<String>();
        var discardedDraftIds = new ArrayList<String>();
        if (request.decisionType() == HumanDecisionType.PROMOTE_DRAFT) {
            promotedCaseIds.addAll(casePromotion.promote(new TestCasePromotionRequest(draftIds, request.actor())).promotedCaseIds());
        } else if (request.decisionType() == HumanDecisionType.DISCARD_DRAFT) {
            discardedDraftIds.addAll(discardDrafts(draftIds));
        }

        recordDraftReviewDecision(
            submission.request(),
            submission.decision(),
            request,
            draftIds,
            promotedCaseIds,
            discardedDraftIds
        );
        var consumption = consumeDecision(new HumanDecisionConsumptionRequest(request.requestId(), "draft-review-workflow"));
        if (consumption.status() == HumanDecisionConsumptionStatus.REJECTED) {
            return HumanDraftReviewDecisionResult.rejected(consumption.blockers(), consumption.auditSummary());
        }

        var resultAudit = new LinkedHashMap<String, Object>();
        resultAudit.putAll(submission.auditSummary());
        resultAudit.put("consumptionStatus", consumption.status().name());
        resultAudit.put("draftIds", draftIds);
        resultAudit.put("promotedCaseIds", promotedCaseIds);
        resultAudit.put("discardedDraftIds", discardedDraftIds);
        if (request.payload().containsKey("changeRequest")) {
            resultAudit.put("changeRequest", request.payload().get("changeRequest"));
        }
        return HumanDraftReviewDecisionResult.applied(
            consumption.request(),
            submission.decision(),
            promotedCaseIds,
            discardedDraftIds,
            compact(resultAudit)
        );
    }

    @Transactional
    public HumanInputResolutionResult applyBlockerResolutionDecision(HumanDecisionSubmissionRequest request) {
        var audit = new LinkedHashMap<String, Object>();
        audit.put("requestId", request == null ? null : request.requestId());
        audit.put("decisionType", request == null || request.decisionType() == null ? null : request.decisionType().name());
        audit.put("actor", request == null ? null : request.actor());
        var humanRequest = request == null || request.requestId().isBlank()
            ? Optional.<HumanReviewRequest>empty()
            : humanRequests.findById(request.requestId());

        var validationBlockers = validateBlockerResolutionDecision(request, humanRequest);
        if (!validationBlockers.isEmpty()) {
            return HumanInputResolutionResult.rejected(validationBlockers, compact(audit));
        }

        var submission = submitDecision(request);
        if (submission.status() == HumanDecisionSubmissionStatus.REJECTED) {
            return HumanInputResolutionResult.rejected(submission.blockers(), submission.auditSummary());
        }

        recordBlockerResolution(submission.request(), submission.decision(), request.payload());
        var consumption = consumeDecision(new HumanDecisionConsumptionRequest(request.requestId(), "blocker-resolution-workflow"));
        if (consumption.status() == HumanDecisionConsumptionStatus.REJECTED) {
            return HumanInputResolutionResult.rejected(consumption.blockers(), consumption.auditSummary());
        }

        var resultAudit = new LinkedHashMap<String, Object>();
        resultAudit.putAll(submission.auditSummary());
        resultAudit.put("consumptionStatus", consumption.status().name());
        resultAudit.put("taskStatus", tasks.findById(submission.request().getTaskId()).orElseThrow().getStatus().name());
        return HumanInputResolutionResult.applied(
            consumption.request(),
            submission.decision(),
            compact(resultAudit)
        );
    }

    @Transactional
    public HumanHighRiskDecisionResult applyHighRiskDecision(HumanDecisionSubmissionRequest request) {
        var audit = new LinkedHashMap<String, Object>();
        audit.put("requestId", request == null ? null : request.requestId());
        audit.put("decisionType", request == null || request.decisionType() == null ? null : request.decisionType().name());
        audit.put("actor", request == null ? null : request.actor());
        var humanRequest = request == null || request.requestId().isBlank()
            ? Optional.<HumanReviewRequest>empty()
            : humanRequests.findById(request.requestId());

        var validationBlockers = validateHighRiskDecision(request, humanRequest);
        if (!validationBlockers.isEmpty()) {
            return HumanHighRiskDecisionResult.rejected(validationBlockers, compact(audit));
        }

        var submission = submitDecision(request);
        if (submission.status() == HumanDecisionSubmissionStatus.REJECTED) {
            return HumanHighRiskDecisionResult.rejected(submission.blockers(), submission.auditSummary());
        }

        if (request.decisionType() == HumanDecisionType.REJECT) {
            recordHighRiskDecision(submission.request(), submission.decision(), false);
            submission.request().markRejected();
            var rejectedRequest = humanRequests.save(submission.request());

            var resultAudit = new LinkedHashMap<String, Object>();
            resultAudit.putAll(submission.auditSummary());
            resultAudit.put("requestStatus", rejectedRequest.getStatus().name());
            resultAudit.put("taskStatus", tasks.findById(rejectedRequest.getTaskId()).orElseThrow().getStatus().name());
            return HumanHighRiskDecisionResult.denied(
                rejectedRequest,
                submission.decision(),
                compact(resultAudit)
            );
        }

        recordHighRiskDecision(submission.request(), submission.decision(), true);
        var consumption = consumeDecision(new HumanDecisionConsumptionRequest(request.requestId(), "high-risk-approval-workflow"));
        if (consumption.status() == HumanDecisionConsumptionStatus.REJECTED) {
            return HumanHighRiskDecisionResult.rejected(consumption.blockers(), consumption.auditSummary());
        }

        var resultAudit = new LinkedHashMap<String, Object>();
        resultAudit.putAll(submission.auditSummary());
        resultAudit.put("consumptionStatus", consumption.status().name());
        resultAudit.put("taskStatus", tasks.findById(submission.request().getTaskId()).orElseThrow().getStatus().name());
        return HumanHighRiskDecisionResult.approved(
            consumption.request(),
            submission.decision(),
            compact(resultAudit)
        );
    }

    @Transactional
    public HumanPlannerClarificationResult applyPlannerClarificationDecision(HumanDecisionSubmissionRequest request) {
        var audit = new LinkedHashMap<String, Object>();
        audit.put("requestId", request == null ? null : request.requestId());
        audit.put("decisionType", request == null || request.decisionType() == null ? null : request.decisionType().name());
        audit.put("actor", request == null ? null : request.actor());
        var humanRequest = request == null || request.requestId().isBlank()
            ? Optional.<HumanReviewRequest>empty()
            : humanRequests.findById(request.requestId());

        var validationBlockers = validatePlannerClarificationDecision(request, humanRequest);
        if (!validationBlockers.isEmpty()) {
            return HumanPlannerClarificationResult.rejected(validationBlockers, compact(audit));
        }

        var submission = submitDecision(request);
        if (submission.status() == HumanDecisionSubmissionStatus.REJECTED) {
            return HumanPlannerClarificationResult.rejected(submission.blockers(), submission.auditSummary());
        }

        var humanInput = recordPlannerClarification(submission.request(), submission.decision(), request.payload());
        var consumption = consumeDecision(new HumanDecisionConsumptionRequest(request.requestId(), "planner-clarification-workflow"));
        if (consumption.status() == HumanDecisionConsumptionStatus.REJECTED) {
            return HumanPlannerClarificationResult.rejected(consumption.blockers(), consumption.auditSummary());
        }

        var resultAudit = new LinkedHashMap<String, Object>();
        resultAudit.putAll(submission.auditSummary());
        resultAudit.put("consumptionStatus", consumption.status().name());
        resultAudit.put("recoveryTrigger", "HUMAN_INPUT_REQUIRED");
        resultAudit.put("taskStatus", tasks.findById(submission.request().getTaskId()).orElseThrow().getStatus().name());
        return HumanPlannerClarificationResult.applied(
            consumption.request(),
            submission.decision(),
            humanInput,
            "HUMAN_INPUT_REQUIRED",
            compact(resultAudit)
        );
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

    private List<Map<String, Object>> draftReviewSchema() {
        return List.of(
            Map.of("name", "reviewDecision", "type", "string", "required", true),
            Map.of("name", "draftIds", "type", "array", "required", true),
            Map.of("name", "changeRequest", "type", "string", "required", false)
        );
    }

    private String humanRequestIdempotencyKey(
        String taskId,
        HumanRequestType requestType,
        String trigger,
        String sourceStepId,
        List<String> pendingDraftIds
    ) {
        return taskId
            + "|" + requestType.name()
            + "|" + trigger
            + "|" + (sourceStepId == null ? "" : sourceStepId)
            + "|" + String.join(";", pendingDraftIds == null ? List.of() : pendingDraftIds);
    }

    private List<String> validateDraftReviewDecision(
        HumanDecisionSubmissionRequest request,
        Optional<HumanReviewRequest> humanRequest
    ) {
        if (request == null || request.requestId().isBlank()) {
            return List.of("Human request id is required");
        }
        if (humanRequest.isEmpty()) {
            return List.of("Human request not found: " + request.requestId());
        }
        var blockers = new ArrayList<String>();
        if (humanRequest.get().getRequestType() != HumanRequestType.DRAFT_REVIEW) {
            blockers.add("Human request is not a draft review request: " + humanRequest.get().getRequestType().name());
        }
        if (humanRequest.get().getStatus() != HumanRequestStatus.PENDING) {
            blockers.add("Human request is not pending: " + humanRequest.get().getStatus().name());
        }
        if (!draftReviewDecisionType(request.decisionType())) {
            blockers.add("Unsupported draft review decision type: " + (request.decisionType() == null ? "null" : request.decisionType().name()));
        }

        var draftIds = draftIds(request.payload().get("draftIds"));
        if (draftIds.isEmpty()) {
            blockers.add("Draft review decision requires at least one draft id");
        }
        var allowedDraftIds = metadataList(humanRequest.get().getMetadata().get("pendingDraftIds"));
        var unexpectedDraftIds = draftIds.stream()
            .filter(draftId -> !allowedDraftIds.isEmpty() && !allowedDraftIds.contains(draftId))
            .toList();
        if (!unexpectedDraftIds.isEmpty()) {
            blockers.add("Draft review decision references drafts outside the request: " + String.join(",", unexpectedDraftIds));
        }
        var persistedDrafts = drafts.findAllById(draftIds);
        var persistedById = new LinkedHashMap<String, TestCaseDraft>();
        persistedDrafts.forEach(draft -> persistedById.put(draft.getDraftId(), draft));
        for (var draftId : draftIds) {
            var draft = persistedById.get(draftId);
            if (draft == null) {
                blockers.add("TestCaseDraft not found: " + draftId);
            } else if (!humanRequest.get().getTaskId().equals(draft.getTaskId())) {
                blockers.add("TestCaseDraft does not belong to task: " + draftId);
            } else if (draft.getStatus() != DraftStatus.PENDING_REVIEW) {
                blockers.add("TestCaseDraft is not pending review: " + draftId);
            }
        }
        if (request.decisionType() == HumanDecisionType.REQUEST_CHANGES
            && metadataString(request.payload().get("changeRequest")).isBlank()) {
            blockers.add("Change request is required when requesting draft changes");
        }
        return List.copyOf(blockers);
    }

    private boolean draftReviewDecisionType(HumanDecisionType decisionType) {
        return decisionType == HumanDecisionType.PROMOTE_DRAFT
            || decisionType == HumanDecisionType.DISCARD_DRAFT
            || decisionType == HumanDecisionType.REQUEST_CHANGES;
    }

    private List<String> validateBlockerResolutionDecision(
        HumanDecisionSubmissionRequest request,
        Optional<HumanReviewRequest> humanRequest
    ) {
        if (request == null || request.requestId().isBlank()) {
            return List.of("Human request id is required");
        }
        if (humanRequest.isEmpty()) {
            return List.of("Human request not found: " + request.requestId());
        }

        var blockers = new ArrayList<String>();
        if (!blockerResolutionRequestType(humanRequest.get().getRequestType())) {
            blockers.add("Human request is not a blocker resolution request: " + humanRequest.get().getRequestType().name());
        }
        if (humanRequest.get().getStatus() != HumanRequestStatus.PENDING) {
            blockers.add("Human request is not pending: " + humanRequest.get().getStatus().name());
        }
        if (!blockerResolutionDecisionType(request.decisionType())) {
            blockers.add("Unsupported blocker resolution decision type: "
                + (request.decisionType() == null ? "null" : request.decisionType().name()));
        }
        var task = tasks.findById(humanRequest.get().getTaskId());
        if (task.isEmpty()) {
            blockers.add("Task not found: " + humanRequest.get().getTaskId());
        } else if (task.get().getStatus() == TaskStatus.COMPLETED) {
            blockers.add("Completed task cannot consume blocker resolution decision");
        } else if (task.get().getStatus() == TaskStatus.CANCELLED) {
            blockers.add("Cancelled task cannot consume blocker resolution decision");
        }
        return List.copyOf(blockers);
    }

    private boolean blockerResolutionRequestType(HumanRequestType requestType) {
        return requestType == HumanRequestType.MISSING_INPUT
            || requestType == HumanRequestType.BLOCKER_RESOLUTION;
    }

    private boolean blockerResolutionDecisionType(HumanDecisionType decisionType) {
        return decisionType == HumanDecisionType.PROVIDE_INPUT
            || decisionType == HumanDecisionType.RESOLVE_BLOCKER;
    }

    private List<String> validateHighRiskDecision(
        HumanDecisionSubmissionRequest request,
        Optional<HumanReviewRequest> humanRequest
    ) {
        if (request == null || request.requestId().isBlank()) {
            return List.of("Human request id is required");
        }
        if (humanRequest.isEmpty()) {
            return List.of("Human request not found: " + request.requestId());
        }

        var blockers = new ArrayList<String>();
        if (humanRequest.get().getRequestType() != HumanRequestType.HIGH_RISK_APPROVAL) {
            blockers.add("Human request is not a high-risk approval request: " + humanRequest.get().getRequestType().name());
        }
        if (humanRequest.get().getStatus() != HumanRequestStatus.PENDING) {
            blockers.add("Human request is not pending: " + humanRequest.get().getStatus().name());
        }
        if (!highRiskDecisionType(request.decisionType())) {
            blockers.add("Unsupported high-risk approval decision type: "
                + (request.decisionType() == null ? "null" : request.decisionType().name()));
        }
        if (request.decisionType() == HumanDecisionType.APPROVE && !Boolean.TRUE.equals(request.payload().get("approved"))) {
            blockers.add("High-risk approval requires approved=true");
        }
        if (request.decisionType() == HumanDecisionType.REJECT && metadataString(request.reason()).isBlank()) {
            blockers.add("High-risk rejection reason is required");
        }
        var task = tasks.findById(humanRequest.get().getTaskId());
        if (task.isEmpty()) {
            blockers.add("Task not found: " + humanRequest.get().getTaskId());
        } else if (task.get().getStatus() == TaskStatus.COMPLETED) {
            blockers.add("Completed task cannot consume high-risk approval decision");
        } else if (task.get().getStatus() == TaskStatus.CANCELLED) {
            blockers.add("Cancelled task cannot consume high-risk approval decision");
        }
        return List.copyOf(blockers);
    }

    private boolean highRiskDecisionType(HumanDecisionType decisionType) {
        return decisionType == HumanDecisionType.APPROVE
            || decisionType == HumanDecisionType.REJECT;
    }

    private List<String> validatePlannerClarificationDecision(
        HumanDecisionSubmissionRequest request,
        Optional<HumanReviewRequest> humanRequest
    ) {
        if (request == null || request.requestId().isBlank()) {
            return List.of("Human request id is required");
        }
        if (humanRequest.isEmpty()) {
            return List.of("Human request not found: " + request.requestId());
        }

        var blockers = new ArrayList<String>();
        if (humanRequest.get().getRequestType() != HumanRequestType.PLANNER_CLARIFICATION) {
            blockers.add("Human request is not a planner clarification request: " + humanRequest.get().getRequestType().name());
        }
        if (humanRequest.get().getStatus() != HumanRequestStatus.PENDING) {
            blockers.add("Human request is not pending: " + humanRequest.get().getStatus().name());
        }
        if (request.decisionType() != HumanDecisionType.PROVIDE_INPUT) {
            blockers.add("Unsupported planner clarification decision type: "
                + (request.decisionType() == null ? "null" : request.decisionType().name()));
        }
        if (!hasMeaningfulPayload(request.payload())) {
            blockers.add("Planner clarification answer is required");
        }
        var task = tasks.findById(humanRequest.get().getTaskId());
        if (task.isEmpty()) {
            blockers.add("Task not found: " + humanRequest.get().getTaskId());
        } else if (task.get().getStatus() == TaskStatus.COMPLETED) {
            blockers.add("Completed task cannot consume planner clarification decision");
        } else if (task.get().getStatus() == TaskStatus.CANCELLED) {
            blockers.add("Cancelled task cannot consume planner clarification decision");
        }
        return List.copyOf(blockers);
    }

    private boolean hasMeaningfulPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        return payload.values().stream().anyMatch(value -> {
            if (value == null) {
                return false;
            }
            if (value instanceof String string) {
                return !string.isBlank();
            }
            if (value instanceof List<?> list) {
                return !list.isEmpty();
            }
            if (value instanceof Map<?, ?> map) {
                return !map.isEmpty();
            }
            return true;
        });
    }

    private List<String> draftIds(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        var result = new LinkedHashSet<String>();
        for (var item : values) {
            var draftId = metadataString(item);
            if (!draftId.isBlank()) {
                result.add(draftId);
            }
        }
        return List.copyOf(result);
    }

    private List<String> discardDrafts(List<String> draftIds) {
        var discarded = new ArrayList<String>();
        for (var draftId : draftIds) {
            var draft = drafts.findById(draftId).orElseThrow();
            draft.setStatus(DraftStatus.DISCARDED);
            drafts.save(draft);
            discarded.add(draftId);
        }
        return List.copyOf(discarded);
    }

    private void recordDraftReviewDecision(
        HumanReviewRequest request,
        HumanDecisionRecord decision,
        HumanDecisionSubmissionRequest submission,
        List<String> draftIds,
        List<String> promotedCaseIds,
        List<String> discardedDraftIds
    ) {
        var task = tasks.findById(request.getTaskId()).orElseThrow();
        var metadata = mutableMetadata(task);
        addMetadataValues(metadata, "promotedCaseIds", promotedCaseIds);
        addMetadataValues(metadata, "selectedCaseIds", promotedCaseIds);
        addMetadataValues(metadata, "discardedDraftIds", discardedDraftIds);
        if (submission.decisionType() == HumanDecisionType.REQUEST_CHANGES) {
            task.setStatus(TaskStatus.WAITING_FOR_REVIEW);
            var changeRequest = new LinkedHashMap<String, Object>();
            changeRequest.put("requestId", request.getRequestId());
            changeRequest.put("decisionId", decision.getDecisionId());
            changeRequest.put("actor", decision.getActor());
            changeRequest.put("reason", decision.getReason());
            changeRequest.put("draftIds", draftIds);
            changeRequest.put("changeRequest", metadataString(submission.payload().get("changeRequest")));
            changeRequest.put("keepsTaskWaiting", true);
            appendMetadataRecord(metadata, "draftReviewChangeRequests", compact(changeRequest));
        }
        var lastDecision = new LinkedHashMap<String, Object>();
        lastDecision.put("requestId", request.getRequestId());
        lastDecision.put("decisionId", decision.getDecisionId());
        lastDecision.put("decisionType", submission.decisionType().name());
        lastDecision.put("actor", decision.getActor());
        lastDecision.put("reason", decision.getReason());
        lastDecision.put("draftIds", draftIds);
        lastDecision.put("promotedCaseIds", promotedCaseIds);
        lastDecision.put("discardedDraftIds", discardedDraftIds);
        lastDecision.entrySet().removeIf(entry -> entry.getValue() == null);
        metadata.put("lastDraftReviewDecision", Map.copyOf(lastDecision));
        task.setMetadata(Map.copyOf(metadata));
        tasks.save(task);
    }

    private Map<String, Object> mutableMetadata(Task task) {
        return task.getMetadata() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(task.getMetadata());
    }

    private void addMetadataValues(Map<String, Object> metadata, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        var merged = new LinkedHashSet<>(metadataList(metadata.get(key)));
        merged.addAll(values);
        metadata.put(key, List.copyOf(merged));
    }

    private void appendMetadataRecord(Map<String, Object> metadata, String key, Map<String, Object> record) {
        var records = new ArrayList<Map<String, Object>>();
        var existing = metadata.get(key);
        if (existing instanceof List<?> values) {
            for (var value : values) {
                if (value instanceof Map<?, ?> map) {
                    var copied = new LinkedHashMap<String, Object>();
                    map.forEach((entryKey, entryValue) -> copied.put(String.valueOf(entryKey), entryValue));
                    records.add(Map.copyOf(copied));
                }
            }
        }
        records.add(Map.copyOf(record));
        metadata.put(key, List.copyOf(records));
    }

    private void recordBlockerResolution(
        HumanReviewRequest request,
        HumanDecisionRecord decision,
        Map<String, Object> payload
    ) {
        var task = tasks.findById(request.getTaskId()).orElseThrow();
        var metadata = mutableMetadata(task);
        metadata.remove("requiredHumanInput");
        mergeMetadataMap(metadata, "humanInputContext", payload);
        mergeMetadataMap(metadata, "humanInputContextSummary", decision.getSanitizedPayloadSummary());

        var record = new LinkedHashMap<String, Object>();
        record.put("requestId", request.getRequestId());
        record.put("decisionId", decision.getDecisionId());
        record.put("requestType", request.getRequestType().name());
        record.put("decisionType", decision.getDecisionType().name());
        record.put("actor", decision.getActor());
        record.put("reason", decision.getReason());
        record.put("sourceTrigger", request.getSourceTrigger());
        record.put("sourceStepId", request.getSourceStepId());
        record.put("policyReason", request.getPolicyReason());
        record.put("blockers", request.getMetadata().get("blockers"));
        record.put("payloadSummary", decision.getSanitizedPayloadSummary());
        appendMetadataRecord(metadata, "humanInputResolutionRecords", compact(record));
        metadata.put("lastHumanInputResolution", compact(record));
        task.setMetadata(Map.copyOf(metadata));
        task.setStatus(resumeStatusAfterHumanInput(task, metadata));
        tasks.save(task);
    }

    private Map<String, Object> recordPlannerClarification(
        HumanReviewRequest request,
        HumanDecisionRecord decision,
        Map<String, Object> payload
    ) {
        var task = tasks.findById(request.getTaskId()).orElseThrow();
        var metadata = mutableMetadata(task);
        metadata.remove("requiredHumanInput");
        mergeMetadataMap(metadata, "plannerClarificationContext", payload);
        mergeMetadataMap(metadata, "plannerClarificationContextSummary", decision.getSanitizedPayloadSummary());

        var record = new LinkedHashMap<String, Object>();
        record.put("requestId", request.getRequestId());
        record.put("decisionId", decision.getDecisionId());
        record.put("decisionType", decision.getDecisionType().name());
        record.put("actor", decision.getActor());
        record.put("reason", decision.getReason());
        record.put("sourceTrigger", request.getSourceTrigger());
        record.put("sourceStepId", request.getSourceStepId());
        record.put("policyReason", request.getPolicyReason());
        record.put("plannerDecisionId", request.getPlannerDecisionId());
        record.put("question", request.getMetadata().get("question"));
        record.put("options", request.getMetadata().get("options"));
        record.put("blockers", request.getMetadata().get("blockers"));
        record.put("payloadSummary", decision.getSanitizedPayloadSummary());
        var compactRecord = compact(record);
        appendMetadataRecord(metadata, "plannerClarificationRecords", compactRecord);
        metadata.put("lastPlannerClarification", compactRecord);
        task.setMetadata(Map.copyOf(metadata));
        task.setStatus(resumeStatusAfterHumanInput(task, metadata));
        tasks.save(task);

        return Map.of("plannerClarification", compactRecord);
    }

    private void recordHighRiskDecision(
        HumanReviewRequest request,
        HumanDecisionRecord decision,
        boolean approved
    ) {
        var task = tasks.findById(request.getTaskId()).orElseThrow();
        var metadata = mutableMetadata(task);
        metadata.remove("requiredHumanInput");

        var record = new LinkedHashMap<String, Object>();
        record.put("requestId", request.getRequestId());
        record.put("decisionId", decision.getDecisionId());
        record.put("decisionType", decision.getDecisionType().name());
        record.put("approved", approved);
        record.put("actor", decision.getActor());
        record.put("reason", decision.getReason());
        record.put("riskLevel", request.getRiskLevel().name());
        record.put("sourceTrigger", request.getSourceTrigger());
        record.put("sourceStepId", request.getSourceStepId());
        record.put("policyReason", request.getPolicyReason());
        record.put("plannerDecisionId", request.getPlannerDecisionId());
        record.put("plannerInputTraceId", request.getMetadata().get("plannerInputTraceId"));
        record.put("blockers", request.getMetadata().get("blockers"));
        record.put("payloadSummary", decision.getSanitizedPayloadSummary());
        var compactRecord = compact(record);

        appendMetadataRecord(metadata, "highRiskDecisionRecords", compactRecord);
        metadata.put("lastHighRiskDecision", compactRecord);
        if (approved) {
            metadata.put("highRiskApprovalContext", compactRecord);
            task.setStatus(resumeStatusAfterHumanInput(task, metadata));
        } else {
            metadata.put("highRiskRejectionContext", compactRecord);
            task.setStatus(TaskStatus.FAILED);
        }
        task.setMetadata(Map.copyOf(metadata));
        tasks.save(task);
    }

    @SuppressWarnings("unchecked")
    private void mergeMetadataMap(Map<String, Object> metadata, String key, Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        var merged = new LinkedHashMap<String, Object>();
        var existing = metadata.get(key);
        if (existing instanceof Map<?, ?> map) {
            map.forEach((entryKey, entryValue) -> merged.put(String.valueOf(entryKey), entryValue));
        }
        values.forEach(merged::put);
        metadata.put(key, Map.copyOf(merged));
    }

    private TaskStatus resumeStatusAfterHumanInput(Task task, Map<String, Object> metadata) {
        var lastReplanning = metadata.get("lastReplanning");
        if (lastReplanning instanceof Map<?, ?> values) {
            var resumeStatus = values.get("resumeTaskStatus");
            if (resumeStatus != null) {
                try {
                    return TaskStatus.valueOf(resumeStatus.toString());
                } catch (IllegalArgumentException ignored) {
                    return task.getStatus();
                }
            }
        }
        return task.getStatus();
    }

    private List<String> metadataList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .map(this::metadataString)
            .filter(item -> !item.isBlank())
            .distinct()
            .toList();
    }

    private Map<String, Object> compact(Map<String, Object> source) {
        var compacted = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                compacted.put(key, value);
            }
        });
        return Map.copyOf(compacted);
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
