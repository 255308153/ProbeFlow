package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.knowledge.EmbeddingProfileMetadata;
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
class MemoryFactDedupEvidenceMergeIssue03Tests {

    @Autowired
    private MemoryRefineryService memoryRefineryService;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Test
    void repeatedSameSourceTypeRefAndTaskIsIdempotentWithoutInflatingEvidence() {
        var first = memoryRefineryService.refine(paymentTimeout(
            "issue03-same-source",
            "issue03-task-a",
            "Payment timeout requires retry fixture",
            "Payment timeout failures require the approved retry fixture before assertions.",
            0.82f,
            "First evidence for GW_TIMEOUT.",
            Map.of("module", "payment", "apiPath", "/api/payments/charge", "errorCode", "GW_TIMEOUT")
        ));
        var second = memoryRefineryService.refine(paymentTimeout(
            "issue03-same-source",
            "issue03-task-a",
            "Payment timeout requires retry fixture with extra diagnostics",
            "Payment timeout failures require the approved retry fixture before assertions and logging.",
            0.92f,
            "Duplicate retry from same source should not alter evidence.",
            Map.of("module", "payment", "apiPath", "/api/payments/charge", "errorCode", "GW_TIMEOUT")
        ));

        assertThat(second.created()).isFalse();
        assertThat(second.duplicateSuppressed()).isTrue();
        assertThat(second.memory().memoryId()).isEqualTo(first.memory().memoryId());
        assertThat(second.memory().metadata())
            .containsEntry("mergeCount", 1)
            .containsEntry("evidenceCount", 1);
        assertThat(longTermMemories.findAll()).hasSize(1);
    }

    @Test
    void sameFactFingerprintFromDifferentSourceMergesEvidenceAndRegeneratesEmbedding() {
        var first = memoryRefineryService.refine(paymentTimeout(
            "issue03-fingerprint-a",
            "issue03-task-b1",
            "Payment timeout requires retry fixture",
            "Payment timeout failures require the approved retry fixture before assertions.",
            0.80f,
            "Execution A confirmed GW_TIMEOUT recovery with retry fixture.",
            Map.of("module", "payment", "apiPath", "/api/payments/charge", "errorCode", "GW_TIMEOUT")
        ));
        var beforeEmbedding = first.memory().embedding().clone();
        var fingerprint = first.memory().metadata().get("factFingerprint");

        var merged = memoryRefineryService.refine(paymentTimeout(
            "issue03-fingerprint-b",
            "issue03-task-b2",
            "Payment timeout requires retry fixture",
            "Payment timeout failures require the approved retry fixture before assertions.",
            0.90f,
            "Execution B confirmed the same GW_TIMEOUT recovery.",
            Map.of("module", "payment", "apiPath", "/api/payments/charge", "errorCode", "GW_TIMEOUT")
        ));

        assertThat(merged.created()).isFalse();
        assertThat(merged.duplicateSuppressed()).isTrue();
        assertThat(merged.memory().memoryId()).isEqualTo(first.memory().memoryId());
        assertThat(merged.memory().confidence()).isGreaterThan(0.90f).isLessThanOrEqualTo(0.95f);
        assertThat(merged.memory().importance()).isGreaterThan(first.memory().importance()).isLessThanOrEqualTo(0.97f);
        assertThat(merged.memory().successContribution()).isGreaterThan(first.memory().successContribution()).isLessThanOrEqualTo(0.85f);
        assertThat(merged.memory().embedding()).hasSize(1024).isNotEqualTo(beforeEmbedding);
        assertThat(merged.memory().metadata())
            .containsEntry("factFingerprint", fingerprint)
            .containsEntry("mergeCount", 2)
            .containsEntry("evidenceCount", 2)
            .containsKey(EmbeddingProfileMetadata.EMBEDDING_PROFILE);
        assertThat((List<String>) merged.memory().metadata().get("mergedSourceRefs"))
            .contains("issue03-fingerprint-a", "issue03-fingerprint-b");
        assertThat((List<Map<String, Object>>) merged.memory().metadata().get("evidenceLedger"))
            .hasSize(2);
        assertThat((List<String>) merged.memory().metadata().get("mergedFactFingerprints"))
            .contains(fingerprint.toString());
    }

    @Test
    void similarFactMergesOnlyWhenIdentityHintsMatch() {
        var first = memoryRefineryService.refine(paymentTimeout(
            "issue03-similar-a",
            "issue03-task-c1",
            "Gateway timeout requires shorter retry windows",
            "Retry gateway timeouts with a tighter assertion and retry window.",
            0.78f,
            "Gateway timeout evidence from first execution.",
            Map.of("systemName", "billing", "module", "payment", "apiPath", "/api/payments/charge", "errorCode", "GW_TIMEOUT")
        ));
        var similar = memoryRefineryService.refine(paymentTimeout(
            "issue03-similar-b",
            "issue03-task-c2",
            "Payment gateway timeout needs tighter retry window",
            "Use a smaller retry and assertion window for gateway timeout recovery.",
            0.83f,
            "Gateway timeout evidence from second execution.",
            Map.of("systemName", "billing", "module", "payment", "apiPath", "/api/payments/charge", "errorCode", "GW_TIMEOUT")
        ));

        assertThat(similar.created()).isFalse();
        assertThat(similar.memory().memoryId()).isEqualTo(first.memory().memoryId());
        assertThat((List<String>) similar.memory().metadata().get("mergedSourceTypes"))
            .contains("EXECUTION_RESULT");
    }

    private MemoryCandidateRequest paymentTimeout(
        String sourceRef,
        String taskId,
        String summary,
        String content,
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
            List.of("payment", "timeout", "retry"),
            confidence,
            rawEvidence,
            metadata
        );
    }
}
