package com.probeflow.testagent.memory;

import com.probeflow.testagent.agentmemoryfeedback.MemoryFeedbackSanitizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MemoryUsefulnessFeedbackService {

    private static final int SUMMARY_LIMIT = 512;
    private static final float MIN_CONFIDENCE = 0.10f;
    private static final float MAX_CONFIDENCE = 0.98f;
    private static final float MIN_IMPORTANCE = 0.10f;
    private static final float MAX_IMPORTANCE = 0.95f;
    private static final float MIN_SUCCESS_CONTRIBUTION = 0.0f;
    private static final float MAX_SUCCESS_CONTRIBUTION = 0.95f;
    private static final long NEGATIVE_FEEDBACK_INACTIVE_THRESHOLD = 3L;

    private final MemoryUsageRecordRepository usageRecords;
    private final LongTermMemoryRepository longTermMemories;
    private final MemoryUsefulnessFeedbackRepository feedbackRecords;
    private final MemoryFeedbackSanitizer sanitizer;

    public MemoryUsefulnessFeedbackService(
        MemoryUsageRecordRepository usageRecords,
        LongTermMemoryRepository longTermMemories,
        MemoryUsefulnessFeedbackRepository feedbackRecords,
        MemoryFeedbackSanitizer sanitizer
    ) {
        this.usageRecords = usageRecords;
        this.longTermMemories = longTermMemories;
        this.feedbackRecords = feedbackRecords;
        this.sanitizer = sanitizer;
    }

    @Transactional
    public MemoryUsefulnessFeedbackResult submitFeedback(MemoryUsefulnessFeedbackRequest request) {
        var normalized = normalize(request);
        var existing = feedbackRecords.findByUsageIdAndActorAndOutcome(
            normalized.usageId(),
            normalized.actor(),
            normalized.outcome()
        );
        if (existing.isPresent()) {
            var feedback = existing.get();
            var memory = longTermMemories.findById(feedback.getMemoryId()).orElse(null);
            return new MemoryUsefulnessFeedbackResult(
                MemoryUsefulnessFeedbackStatus.DUPLICATE,
                feedback.getRejectionReason(),
                feedback,
                memory
            );
        }

        var usage = usageRecords.findById(normalized.usageId())
            .orElseThrow(() -> new IllegalArgumentException("MemoryUsageRecord not found: " + normalized.usageId()));
        var memory = longTermMemories.findById(usage.getMemoryId())
            .orElseThrow(() -> new IllegalArgumentException("LongTermMemory not found: " + usage.getMemoryId()));
        var previousStatus = memory.getStatus();

        if (previousStatus == MemoryStatus.ARCHIVED) {
            var rejected = buildFeedback(
                normalized,
                usage,
                memory,
                MemoryUsefulnessFeedbackStatus.REJECTED,
                "archived-memory-does-not-accept-usefulness-feedback",
                0.0f,
                0.0f,
                0.0f,
                previousStatus,
                previousStatus
            );
            return new MemoryUsefulnessFeedbackResult(
                MemoryUsefulnessFeedbackStatus.REJECTED,
                rejected.getRejectionReason(),
                feedbackRecords.save(rejected),
                memory
            );
        }

        var beforeConfidence = memory.getConfidence();
        var beforeImportance = memory.getImportance();
        var beforeSuccess = memory.getSuccessContribution();

        var deltas = deltasFor(normalized.outcome());
        memory.setConfidence(bound(beforeConfidence + deltas.confidence(), MIN_CONFIDENCE, MAX_CONFIDENCE));
        memory.setImportance(bound(beforeImportance + deltas.importance(), MIN_IMPORTANCE, MAX_IMPORTANCE));
        memory.setSuccessContribution(bound(
            beforeSuccess + deltas.successContribution(),
            MIN_SUCCESS_CONTRIBUTION,
            MAX_SUCCESS_CONTRIBUTION
        ));
        memory.setLastUsedAt(Instant.now());

        var negativeFeedbackCount = normalized.outcome() == MemoryUsefulnessOutcome.NEGATIVE
            ? feedbackRecords.countByMemoryIdAndOutcomeAndStatus(
                memory.getMemoryId(),
                MemoryUsefulnessOutcome.NEGATIVE,
                MemoryUsefulnessFeedbackStatus.RECORDED
            ) + 1L
            : 0L;
        if (negativeFeedbackCount >= NEGATIVE_FEEDBACK_INACTIVE_THRESHOLD) {
            memory.setStatus(MemoryStatus.INACTIVE);
        }

        var savedMemory = longTermMemories.save(memory);
        var feedback = buildFeedback(
            normalized,
            usage,
            savedMemory,
            MemoryUsefulnessFeedbackStatus.RECORDED,
            null,
            savedMemory.getConfidence() - beforeConfidence,
            savedMemory.getImportance() - beforeImportance,
            savedMemory.getSuccessContribution() - beforeSuccess,
            previousStatus,
            savedMemory.getStatus()
        );
        return new MemoryUsefulnessFeedbackResult(
            MemoryUsefulnessFeedbackStatus.RECORDED,
            null,
            feedbackRecords.save(feedback),
            savedMemory
        );
    }

    private MemoryUsefulnessFeedback buildFeedback(
        MemoryUsefulnessFeedbackRequest request,
        MemoryUsageRecord usage,
        LongTermMemory memory,
        MemoryUsefulnessFeedbackStatus status,
        String rejectionReason,
        float confidenceDelta,
        float importanceDelta,
        float successContributionDelta,
        MemoryStatus previousStatus,
        MemoryStatus newStatus
    ) {
        var reason = sanitizer.sanitizeText(request.reason());
        var feedback = new MemoryUsefulnessFeedback();
        feedback.setUsageId(usage.getUsageId());
        feedback.setMemoryId(memory.getMemoryId());
        feedback.setTaskId(usage.getTaskId());
        feedback.setActor(request.actor());
        feedback.setOutcome(request.outcome());
        feedback.setStatus(status);
        feedback.setReason(reason);
        feedback.setRejectionReason(rejectionReason);
        feedback.setSanitizedSummary(limit(summary(request.outcome(), reason, confidenceDelta, importanceDelta, successContributionDelta)));
        feedback.setConfidenceDelta(confidenceDelta);
        feedback.setImportanceDelta(importanceDelta);
        feedback.setSuccessContributionDelta(successContributionDelta);
        feedback.setPreviousStatus(previousStatus);
        feedback.setNewStatus(newStatus);
        feedback.setMetadata(metadata(request, usage, previousStatus, newStatus));
        return feedback;
    }

    private Map<String, Object> metadata(MemoryUsefulnessFeedbackRequest request, MemoryUsageRecord usage, MemoryStatus previousStatus, MemoryStatus newStatus) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.putAll(sanitizer.sanitizeMap(request.metadata()));
        metadata.put("usageConsumer", usage.getConsumer().name());
        metadata.put("stageProfile", usage.getStageProfile());
        metadata.put("usageSourceRef", usage.getSourceRef());
        metadata.put("previousStatus", previousStatus.name());
        metadata.put("newStatus", newStatus.name());
        return metadata;
    }

    private String summary(
        MemoryUsefulnessOutcome outcome,
        String reason,
        float confidenceDelta,
        float importanceDelta,
        float successContributionDelta
    ) {
        var summary = "outcome=%s confidenceDelta=%.4f importanceDelta=%.4f successContributionDelta=%.4f".formatted(
            outcome.name(),
            confidenceDelta,
            importanceDelta,
            successContributionDelta
        );
        if (StringUtils.hasText(reason)) {
            summary += " reason=" + reason;
        }
        return sanitizer.sanitizeText(summary);
    }

    private MemoryUsefulnessFeedbackRequest normalize(MemoryUsefulnessFeedbackRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("feedback request must not be null");
        }
        if (!StringUtils.hasText(request.usageId())) {
            throw new IllegalArgumentException("usageId must not be blank");
        }
        if (!StringUtils.hasText(request.actor())) {
            throw new IllegalArgumentException("actor must not be blank");
        }
        return new MemoryUsefulnessFeedbackRequest(
            request.usageId().trim(),
            limit(request.actor().trim(), 128),
            request.outcome() == null ? MemoryUsefulnessOutcome.UNKNOWN : request.outcome(),
            request.reason(),
            request.metadata() == null ? Map.of() : new LinkedHashMap<>(request.metadata())
        );
    }

    private FeedbackDeltas deltasFor(MemoryUsefulnessOutcome outcome) {
        return switch (outcome) {
            case POSITIVE -> new FeedbackDeltas(0.04f, 0.03f, 0.08f);
            case NEGATIVE -> new FeedbackDeltas(-0.07f, -0.04f, -0.10f);
            case NEUTRAL, UNKNOWN -> new FeedbackDeltas(0.0f, 0.0f, 0.0f);
        };
    }

    private float bound(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String limit(String value) {
        if (value == null || value.length() <= SUMMARY_LIMIT) {
            return value;
        }
        return value.substring(0, SUMMARY_LIMIT);
    }

    private String limit(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }

    private record FeedbackDeltas(float confidence, float importance, float successContribution) {
    }
}
