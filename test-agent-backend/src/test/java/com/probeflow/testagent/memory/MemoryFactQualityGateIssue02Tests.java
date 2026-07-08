package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

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
class MemoryFactQualityGateIssue02Tests {

    @Autowired
    private MemoryRefineryService memoryRefineryService;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Test
    void rejectsMajorPollutionReasonsWithoutWritingLongTermMemory() {
        assertRejected(candidate(null, null, null, "issue02-empty", 0.8f, null, Map.of()), "empty-candidate");
        assertRejected(candidate("Low confidence retry note", "Retry may have helped payment once.", "execution-low", "issue02-low", 0.2f, "weak evidence", Map.of()), "low-confidence");
        assertRejected(candidate("Temporary gateway note", "Temporary one-off payment failure from a single run.", "execution-one-off", "issue02-one-off", 0.8f, "single run evidence", Map.of()), "one-off-noise");
        assertRejected(candidate("Task local only note", "Do not reuse this task-local only scratch decision.", "execution-local", "issue02-local", 0.8f, "task-local only", Map.of()), "task-local-only");
        assertRejected(new MemoryCandidateRequest(
            "No reusable state note",
            "The current task has a panel open and a cursor near the retry button.",
            MemorySourceType.TASK_STATE,
            "task-state-local",
            "issue02-no-reusable",
            List.of("local-ui-state"),
            0.8f,
            "local state evidence",
            Map.of()
        ), "no-reusable-fact");
        assertRejected(candidate("Payment timeout guidance", "Retry payment timeout checks with stable fixtures.", null, "issue02-missing-evidence", 0.8f, null, Map.of("module", "payment")), "missing-evidence");
        assertRejected(candidate("Remember this", "Be careful.", "issue02-generic", "issue02-generic-task", 0.8f, "generic evidence", Map.of()), "too-generic");
        assertRejected(candidate("Payment auth secret leaked", "Authorization: Bearer abc123 should be reused.", "issue02-secret", "issue02-secret-task", 0.8f, "password=abc123", Map.of("module", "payment")), "sensitive-content");
        assertThat(longTermMemories.findAll()).isEmpty();
    }

    @Test
    void acceptsReusableFactWithEvidenceAsControlSample() {
        var accepted = memoryRefineryService.refine(candidate(
            "Payment timeout requires retry fixture",
            "Payment timeout failures should use the approved retry fixture before response assertions.",
            "issue02-accepted",
            "issue02-accepted-task",
            0.84f,
            "Execution evidence confirmed GW_TIMEOUT disappears after enabling the retry fixture.",
            Map.of("module", "payment", "errorCode", "GW_TIMEOUT")
        ));

        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.memory().metadata())
            .containsEntry("qualityStatus", "ACCEPTED")
            .containsEntry("factType", "failure_pattern");
        assertThat(longTermMemories.findAll()).hasSize(1);
    }

    private void assertRejected(MemoryCandidateRequest request, String reason) {
        var result = memoryRefineryService.refine(request);
        assertThat(result.accepted()).isFalse();
        assertThat(result.created()).isFalse();
        assertThat(result.duplicateSuppressed()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(reason);
        assertThat(result.memory()).isNull();
    }

    private MemoryCandidateRequest candidate(
        String summary,
        String content,
        String sourceRef,
        String taskId,
        float confidence,
        String rawEvidence,
        Map<String, Object> metadata
    ) {
        return new MemoryCandidateRequest(
            summary,
            content,
            MemorySourceType.EXECUTION_RESULT,
            sourceRef,
            taskId,
            List.of("payment"),
            confidence,
            rawEvidence,
            metadata
        );
    }
}
