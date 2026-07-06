package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentmemoryfeedback.MemoryFeedbackSanitizer;
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
class MemoryUsefulnessFeedbackServiceTests {

    @Autowired
    private MemoryUsefulnessFeedbackService feedbackService;

    @Autowired
    private MemoryUsefulnessFeedbackRepository feedbackRecords;

    @Autowired
    private MemoryUsageRecordRepository usageRecords;

    @Autowired
    private MemoryRefineryService memoryRefineryService;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Test
    void positiveFeedbackIncreasesScoresAndWritesSanitizedAudit() {
        var memory = createMemory("issue07-positive", 0.76f);
        var usage = createUsage(memory, "task-positive", MemoryUsageConsumer.PLANNER);
        var beforeConfidence = memory.getConfidence();
        var beforeImportance = memory.getImportance();
        var beforeSuccess = memory.getSuccessContribution();

        var result = feedbackService.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            usage.getUsageId(),
            "planner",
            MemoryUsefulnessOutcome.POSITIVE,
            "helped avoid retry loop Authorization: bearer raw-token token=abc123",
            Map.of(
                "apiKey", "secret-api-key",
                "note", "cookie=session-secret helped reproduce"
            )
        ));

        assertThat(result.status()).isEqualTo(MemoryUsefulnessFeedbackStatus.RECORDED);
        var saved = longTermMemories.findById(memory.getMemoryId()).orElseThrow();
        assertThat(saved.getConfidence()).isGreaterThan(beforeConfidence);
        assertThat(saved.getImportance()).isGreaterThan(beforeImportance);
        assertThat(saved.getSuccessContribution()).isGreaterThan(beforeSuccess);

        var feedback = result.feedback();
        assertThat(feedback.getUsageId()).isEqualTo(usage.getUsageId());
        assertThat(feedback.getMemoryId()).isEqualTo(memory.getMemoryId());
        assertThat(feedback.getTaskId()).isEqualTo("task-positive");
        assertThat(feedback.getActor()).isEqualTo("planner");
        assertThat(feedback.getOutcome()).isEqualTo(MemoryUsefulnessOutcome.POSITIVE);
        assertThat(feedback.getConfidenceDelta()).isPositive();
        assertThat(feedback.getImportanceDelta()).isPositive();
        assertThat(feedback.getSuccessContributionDelta()).isPositive();
        assertThat(feedback.getReason())
            .contains(MemoryFeedbackSanitizer.MASKED_VALUE)
            .doesNotContain("raw-token")
            .doesNotContain("abc123");
        assertThat(feedback.getSanitizedSummary())
            .contains("outcome=POSITIVE")
            .doesNotContain("raw-token")
            .doesNotContain("abc123");
        assertThat(feedback.getMetadata())
            .containsEntry("usageConsumer", MemoryUsageConsumer.PLANNER.name())
            .containsEntry("apiKey", MemoryFeedbackSanitizer.MASKED_VALUE);
        assertThat(feedback.getMetadata().get("note").toString()).doesNotContain("session-secret");
    }

    @Test
    void negativeFeedbackLowersScoresAndRepeatedNegativeFeedbackDeactivatesMemory() {
        var memory = createMemory("issue07-negative", 0.82f);
        var beforeConfidence = memory.getConfidence();
        var beforeImportance = memory.getImportance();
        var beforeSuccess = memory.getSuccessContribution();

        var first = feedbackService.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            createUsage(memory, "task-negative-1", MemoryUsageConsumer.FAILURE_ANALYSIS).getUsageId(),
            "failure-analysis",
            MemoryUsefulnessOutcome.NEGATIVE,
            "memory suggested the wrong tenant setup",
            Map.of()
        ));

        var afterFirst = longTermMemories.findById(memory.getMemoryId()).orElseThrow();
        assertThat(first.feedback().getConfidenceDelta()).isNegative();
        assertThat(first.feedback().getImportanceDelta()).isNegative();
        assertThat(first.feedback().getSuccessContributionDelta()).isNegative();
        assertThat(afterFirst.getConfidence()).isLessThan(beforeConfidence);
        assertThat(afterFirst.getImportance()).isLessThan(beforeImportance);
        assertThat(afterFirst.getSuccessContribution()).isLessThan(beforeSuccess);
        assertThat(afterFirst.getStatus()).isEqualTo(MemoryStatus.ACTIVE);

        feedbackService.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            createUsage(memory, "task-negative-2", MemoryUsageConsumer.PLANNER).getUsageId(),
            "planner",
            MemoryUsefulnessOutcome.NEGATIVE,
            "planner found it misleading",
            Map.of()
        ));
        var third = feedbackService.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            createUsage(memory, "task-negative-3", MemoryUsageConsumer.TEST_CASE_GENERATION).getUsageId(),
            "human-reviewer",
            MemoryUsefulnessOutcome.NEGATIVE,
            "human rejected this memory as stale",
            Map.of()
        ));

        var afterThird = longTermMemories.findById(memory.getMemoryId()).orElseThrow();
        assertThat(afterThird.getStatus()).isEqualTo(MemoryStatus.INACTIVE);
        assertThat(third.feedback().getPreviousStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(third.feedback().getNewStatus()).isEqualTo(MemoryStatus.INACTIVE);
        assertThat(feedbackRecords.countByMemoryIdAndOutcomeAndStatus(
            memory.getMemoryId(),
            MemoryUsefulnessOutcome.NEGATIVE,
            MemoryUsefulnessFeedbackStatus.RECORDED
        )).isEqualTo(3);
    }

    @Test
    void neutralAndUnknownFeedbackRecordAuditWithoutScoreMovement() {
        var memory = createMemory("issue07-neutral", 0.74f);
        var beforeConfidence = memory.getConfidence();
        var beforeImportance = memory.getImportance();
        var beforeSuccess = memory.getSuccessContribution();

        var neutral = feedbackService.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            createUsage(memory, "task-neutral", MemoryUsageConsumer.CONTEXT_BUILDER).getUsageId(),
            "context-builder",
            MemoryUsefulnessOutcome.NEUTRAL,
            "used as context but no clear effect",
            Map.of()
        ));
        var unknown = feedbackService.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            createUsage(memory, "task-unknown", MemoryUsageConsumer.REPORT_GENERATION).getUsageId(),
            "report-generator",
            null,
            "downstream outcome not measured",
            Map.of()
        ));

        var saved = longTermMemories.findById(memory.getMemoryId()).orElseThrow();
        assertThat(saved.getConfidence()).isEqualTo(beforeConfidence);
        assertThat(saved.getImportance()).isEqualTo(beforeImportance);
        assertThat(saved.getSuccessContribution()).isEqualTo(beforeSuccess);
        assertThat(neutral.feedback().getConfidenceDelta()).isZero();
        assertThat(neutral.feedback().getImportanceDelta()).isZero();
        assertThat(neutral.feedback().getSuccessContributionDelta()).isZero();
        assertThat(unknown.feedback().getOutcome()).isEqualTo(MemoryUsefulnessOutcome.UNKNOWN);
        assertThat(unknown.feedback().getConfidenceDelta()).isZero();
    }

    @Test
    void duplicateFeedbackIsIdempotentAndDoesNotApplyScoreDeltaTwice() {
        var memory = createMemory("issue07-duplicate", 0.8f);
        var usage = createUsage(memory, "task-duplicate", MemoryUsageConsumer.PLANNER);

        var first = feedbackService.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            usage.getUsageId(),
            "planner",
            MemoryUsefulnessOutcome.NEGATIVE,
            "first negative signal",
            Map.of()
        ));
        var afterFirst = longTermMemories.findById(memory.getMemoryId()).orElseThrow();

        var duplicate = feedbackService.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            usage.getUsageId(),
            "planner",
            MemoryUsefulnessOutcome.NEGATIVE,
            "duplicate retry should not change scores",
            Map.of()
        ));
        var afterDuplicate = longTermMemories.findById(memory.getMemoryId()).orElseThrow();

        assertThat(first.status()).isEqualTo(MemoryUsefulnessFeedbackStatus.RECORDED);
        assertThat(duplicate.status()).isEqualTo(MemoryUsefulnessFeedbackStatus.DUPLICATE);
        assertThat(duplicate.feedback().getFeedbackId()).isEqualTo(first.feedback().getFeedbackId());
        assertThat(afterDuplicate.getConfidence()).isEqualTo(afterFirst.getConfidence());
        assertThat(afterDuplicate.getImportance()).isEqualTo(afterFirst.getImportance());
        assertThat(afterDuplicate.getSuccessContribution()).isEqualTo(afterFirst.getSuccessContribution());
        assertThat(feedbackRecords.findAllByMemoryIdOrderByCreatedAtAscFeedbackIdAsc(memory.getMemoryId())).hasSize(1);
    }

    @Test
    void archivedMemoryRejectsFeedbackWithoutChangingScores() {
        var memory = createMemory("issue07-archived", 0.81f);
        memoryRefineryService.archiveMemory(memory.getMemoryId());
        var archived = longTermMemories.findById(memory.getMemoryId()).orElseThrow();
        var usage = createUsage(archived, "task-archived", MemoryUsageConsumer.PLANNER);

        var result = feedbackService.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            usage.getUsageId(),
            "planner",
            MemoryUsefulnessOutcome.POSITIVE,
            "ordinary feedback should be rejected for archived memory",
            Map.of()
        ));

        var saved = longTermMemories.findById(memory.getMemoryId()).orElseThrow();
        assertThat(result.status()).isEqualTo(MemoryUsefulnessFeedbackStatus.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo("archived-memory-does-not-accept-usefulness-feedback");
        assertThat(result.feedback().getStatus()).isEqualTo(MemoryUsefulnessFeedbackStatus.REJECTED);
        assertThat(result.feedback().getConfidenceDelta()).isZero();
        assertThat(result.feedback().getImportanceDelta()).isZero();
        assertThat(result.feedback().getSuccessContributionDelta()).isZero();
        assertThat(saved.getStatus()).isEqualTo(MemoryStatus.ARCHIVED);
        assertThat(saved.getConfidence()).isEqualTo(archived.getConfidence());
        assertThat(saved.getImportance()).isEqualTo(archived.getImportance());
        assertThat(saved.getSuccessContribution()).isEqualTo(archived.getSuccessContribution());
    }

    @Test
    void scoreUpdatesRespectUpperAndLowerBounds() {
        var high = createMemory("issue07-high-bound", 0.93f);
        high.setConfidence(0.97f);
        high.setImportance(0.94f);
        high.setSuccessContribution(0.94f);
        high = longTermMemories.save(high);

        feedbackService.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            createUsage(high, "task-high-bound", MemoryUsageConsumer.PLANNER).getUsageId(),
            "planner",
            MemoryUsefulnessOutcome.POSITIVE,
            "positive near upper bound",
            Map.of()
        ));
        var boundedHigh = longTermMemories.findById(high.getMemoryId()).orElseThrow();
        assertThat(boundedHigh.getConfidence()).isEqualTo(0.98f);
        assertThat(boundedHigh.getImportance()).isEqualTo(0.95f);
        assertThat(boundedHigh.getSuccessContribution()).isEqualTo(0.95f);

        var low = createMemory("issue07-low-bound", 0.72f);
        low.setConfidence(0.11f);
        low.setImportance(0.11f);
        low.setSuccessContribution(0.03f);
        low = longTermMemories.save(low);

        feedbackService.submitFeedback(new MemoryUsefulnessFeedbackRequest(
            createUsage(low, "task-low-bound", MemoryUsageConsumer.PLANNER).getUsageId(),
            "planner",
            MemoryUsefulnessOutcome.NEGATIVE,
            "negative near lower bound",
            Map.of()
        ));
        var boundedLow = longTermMemories.findById(low.getMemoryId()).orElseThrow();
        assertThat(boundedLow.getConfidence()).isEqualTo(0.10f);
        assertThat(boundedLow.getImportance()).isEqualTo(0.10f);
        assertThat(boundedLow.getSuccessContribution()).isEqualTo(0.0f);
    }

    private LongTermMemory createMemory(String sourceRef, float confidence) {
        var result = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Tenant bootstrap prevents PAY_401 " + sourceRef,
            "Bootstrap tenant context before auth checks to avoid PAY_401 during payment execution.",
            MemorySourceType.OBSERVATION,
            sourceRef,
            "task-" + sourceRef,
            List.of("payment", "auth", "tenant"),
            confidence,
            "Observed PAY_401 disappears after tenant bootstrap.",
            Map.of(
                "systemName", "order-platform",
                "module", "payment",
                "apiPath", "/api/orders/{orderId}/pay",
                "errorCode", "PAY_401"
            )
        ));
        return longTermMemories.findById(result.memory().memoryId()).orElseThrow();
    }

    private MemoryUsageRecord createUsage(LongTermMemory memory, String taskId, MemoryUsageConsumer consumer) {
        var usage = new MemoryUsageRecord();
        usage.setMemoryId(memory.getMemoryId());
        usage.setTaskId(taskId);
        usage.setStageProfile("failure_analysis");
        usage.setConsumer(consumer);
        usage.setSourceRef("context-build:" + taskId + ":" + consumer.name().toLowerCase());
        usage.setCitationType("long_term_memory");
        usage.setCitationSourceId(memory.getMemoryId());
        usage.setCitationSourceRef(memory.getSourceRef());
        usage.setScore(0.82d);
        usage.setConfidence(memory.getConfidence());
        usage.setLowConfidence(false);
        usage.setMatchReasons(List.of("structure-match", "tag-match"));
        usage.setMetadata(Map.of(
            "memoryScopeType", memory.getScopeType().name(),
            "memorySourceType", memory.getSourceType().name()
        ));
        return usageRecords.save(usage);
    }
}
