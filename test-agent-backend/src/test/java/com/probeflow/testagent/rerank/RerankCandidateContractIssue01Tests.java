package com.probeflow.testagent.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.probeflow.testagent.knowledge.DocumentAuthority;
import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalHit;
import com.probeflow.testagent.memory.LongTermMemoryRetrievalHit;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RerankCandidateContractIssue01Tests {

    private final RerankCandidateContractService service = new RerankCandidateContractService();

    @Test
    void normalizesKnowledgeAndMemoryCandidatesWithoutReplacingRouteEvidence() {
        var candidates = service.normalize(
            List.of(paymentErrorGuide()),
            List.of(paymentFailureMemory()),
            routeEvidenceFixture()
        );

        assertThat(candidates).hasSize(2);

        var knowledge = candidates.getFirst();
        assertThat(knowledge.corpusType()).isEqualTo(RerankCorpusType.KNOWLEDGE);
        assertThat(knowledge.candidateIdentity().stableKey()).isEqualTo("knowledge:chunk-pay-401");
        assertThat(knowledge.sourceIdentity().sourceIds())
            .containsEntry("chunkId", "chunk-pay-401")
            .containsEntry("documentRevisionId", "rev-payment-v3");
        assertThat(knowledge.routeEvidence().queryVariants()).containsExactly("qv-error-code", "qv-api-path");
        assertThat(knowledge.routeEvidence().matchedRoutes())
            .extracting(RerankMatchedRoute::routeName)
            .containsExactly("metadata-exact", "rewritten-semantic");
        assertThat(knowledge.routeEvidence().routeRanks()).containsEntry("metadata-exact", 1);
        assertThat(knowledge.routeEvidence().routeScores()).containsEntry("metadata-exact", 0.98d);
        assertThat(knowledge.fusedScore()).isEqualTo(0.91d);
        assertThat(knowledge.beforeRank()).isEqualTo(2);
        assertThat(knowledge.budgetEstimateTokens()).isEqualTo(96);
        assertThat(knowledge.missingContextHints()).isEmpty();
        assertThat(knowledge.featureLedger())
            .extracting(
                RerankFeatureLedger::semantic,
                RerankFeatureLedger::metadata,
                RerankFeatureLedger::lexical,
                RerankFeatureLedger::routeAgreement,
                RerankFeatureLedger::exactEntity,
                RerankFeatureLedger::stageFit,
                RerankFeatureLedger::authority,
                RerankFeatureLedger::freshness,
                RerankFeatureLedger::tokenCost
            )
            .containsExactly(0.82d, 0.77d, 0.61d, 0.91d, 1.0d, 0.94d, 1.0d, 0.88d, 96);

        var memory = candidates.get(1);
        assertThat(memory.corpusType()).isEqualTo(RerankCorpusType.MEMORY);
        assertThat(memory.candidateIdentity().stableKey()).isEqualTo("memory:mem-pay-401");
        assertThat(memory.sourceIdentity().sourceIds())
            .containsEntry("memoryId", "mem-pay-401")
            .containsEntry("scopeType", "FAILURE_PATTERN")
            .containsEntry("factFingerprint", "fact-pay-401-bootstrap");
        assertThat(memory.routeEvidence().queryVariants()).containsExactly("qv-failure-pattern", "qv-graph");
        assertThat(memory.featureLedger().memoryConfidence()).isCloseTo(0.91d, within(0.0001d));
        assertThat(memory.featureLedger().memoryImportance()).isCloseTo(0.83d, within(0.0001d));
        assertThat(memory.featureLedger().memorySuccessContribution()).isCloseTo(0.72d, within(0.0001d));
        assertThat(memory.featureLedger().graphConfidence()).isEqualTo(0.86d);
        assertThat(memory.featureLedger().graphPathLength()).isEqualTo(2);
        assertThat(memory.featureLedger().conflictSignals()).containsExactly("contradicts-old-bootstrap-note");
        assertThat(memory.featureLedger().lowConfidence()).isFalse();
        assertThat(memory.budgetEstimateTokens()).isEqualTo(64);
        assertThat(memory.missingContextHints()).isEmpty();
    }

    @Test
    void missingFeaturesBecomeDiagnosticsAndHintsInsteadOfFailures() {
        var candidates = service.normalize(
            List.of(sparseKnowledgeHit()),
            List.of(),
            Map.of()
        );

        assertThat(candidates).hasSize(1);
        var candidate = candidates.getFirst();

        assertThat(candidate.routeEvidence().matchedRoutes()).isEmpty();
        assertThat(candidate.featureLedger().diagnostics())
            .extracting(RerankFeatureDiagnostic::code)
            .contains(
                "missing-route-evidence",
                "missing-feature:semantic",
                "missing-feature:metadata",
                "missing-feature:lexical",
                "missing-feature:routeAgreement",
                "missing-feature:exactEntity",
                "missing-feature:stageFit",
                "missing-feature:freshness",
                "missing-feature:authority",
                "missing-feature:tokenCost"
            );
        assertThat(candidate.missingContextHints())
            .contains(
                "parent-section-not-loaded",
                "missing-route-evidence",
                "missing-feature:semantic"
            );
    }

    @Test
    void recordsStableBeforeAndAfterRankExplanationsWithoutRunningARerankAlgorithm() {
        var candidates = service.normalize(
            List.of(paymentErrorGuide()),
            List.of(paymentFailureMemory()),
            routeEvidenceFixture()
        );

        var output = service.recordOutput(candidates, List.of(
            new RerankScoreAssignment(
                new RerankCandidateIdentity(RerankCorpusType.MEMORY, "mem-pay-401"),
                0.93d,
                List.of("route-agreement", "exact-entity"),
                List.of()
            ),
            new RerankScoreAssignment(
                new RerankCandidateIdentity(RerankCorpusType.KNOWLEDGE, "chunk-pay-401"),
                0.86d,
                List.of("document-authority", "stage-fit"),
                List.of("higher-token-cost")
            )
        ));

        assertThat(output.items()).hasSize(2);
        assertThat(output.items().getFirst().candidate().candidateIdentity().stableKey())
            .isEqualTo("memory:mem-pay-401");
        assertThat(output.items().getFirst().beforeRank()).isEqualTo(1);
        assertThat(output.items().getFirst().afterRank()).isEqualTo(1);
        assertThat(output.items().getFirst().rerankScore()).isEqualTo(0.93d);
        assertThat(output.items().getFirst().scoreExplanation()).isEqualTo(
            "memory:mem-pay-401 | beforeRank=1 | afterRank=1 | rerankScore=0.9300"
                + " | reasons=route-agreement,exact-entity | penalties=none | missingFeatures=none"
        );

        assertThat(output.items().get(1).candidate().candidateIdentity().stableKey())
            .isEqualTo("knowledge:chunk-pay-401");
        assertThat(output.items().get(1).beforeRank()).isEqualTo(2);
        assertThat(output.items().get(1).afterRank()).isEqualTo(2);
        assertThat(output.items().get(1).scoreExplanation()).contains(
            "rerankScore=0.8600",
            "reasons=document-authority,stage-fit",
            "penalties=higher-token-cost",
            "missingFeatures=none"
        );
    }

    private KnowledgeRetrievalHit paymentErrorGuide() {
        return new KnowledgeRetrievalHit(
            "chunk-pay-401",
            "doc-payment-errors",
            "rev-payment-v3",
            "PAY_401 payment auth guidance",
            "PAY_401 means payment auth failed after tenant bootstrap was skipped.",
            "wiki/payment-errors.md",
            DocumentType.ERROR_CODE_GUIDE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "Payment",
            List.of("payment", "auth"),
            List.of("failure_analysis"),
            Map.of(
                "candidateRank", 2,
                "exactEntity", true,
                "freshnessScore", 0.88d
            ),
            96,
            0.82d,
            Map.of(
                "semantic", 0.82d,
                "metadata", 0.77d,
                "lexical", 0.61d,
                "stageFit", 0.94d
            ),
            List.of("rewritten-semantic", "metadata-exact"),
            false
        );
    }

    private KnowledgeRetrievalHit sparseKnowledgeHit() {
        return new KnowledgeRetrievalHit(
            "chunk-sparse",
            "doc-sparse",
            "rev-sparse",
            "Sparse note",
            "Only a small fragment was available.",
            "wiki/sparse.md",
            DocumentType.API_NOTE,
            null,
            "order-platform",
            "payment",
            "Payment",
            List.of(),
            List.of(),
            Map.of("missingContextHints", List.of("parent-section-not-loaded")),
            0,
            0.1d,
            Map.of(),
            List.of(),
            true
        );
    }

    private LongTermMemoryRetrievalHit paymentFailureMemory() {
        return new LongTermMemoryRetrievalHit(
            "mem-pay-401",
            MemoryScopeType.FAILURE_PATTERN,
            "Tenant bootstrap prevents PAY_401",
            "Observed PAY_401 until tenant bootstrap was restored before payment auth.",
            "Observed PAY_401 until tenant bootstrap was restored before payment auth. Evidence: suite run 42.",
            List.of("payment", "tenant", "auth"),
            MemorySourceType.OBSERVATION,
            "ltm/payment/pay-401",
            0.91f,
            0.83f,
            0.72f,
            5,
            Instant.parse("2026-07-01T10:15:30Z"),
            Map.of(
                "candidateRank", 1,
                "retrievalChannel", "graph",
                "graphRelationConfidence", 0.86d,
                "graphRelationPath", List.of("error_observed_on_api", "entity_related_to_memory"),
                "factFingerprint", "fact-pay-401-bootstrap",
                "exactEntity", true,
                "freshnessScore", 0.81d,
                "conflictSignals", List.of("contradicts-old-bootstrap-note")
            ),
            64,
            0.79d,
            Map.of(
                "semantic", 0.79d,
                "metadata", 0.88d,
                "lexical", 0.57d,
                "stageFit", 0.9d
            ),
            List.of("semantic-memory", "graph-memory"),
            false
        );
    }

    private Map<String, RerankRouteEvidence> routeEvidenceFixture() {
        return Map.of(
            "knowledge:chunk-pay-401",
            new RerankRouteEvidence(
                List.of("qv-error-code", "qv-api-path"),
                List.of(
                    new RerankMatchedRoute("metadata-exact", 1, 0.98d),
                    new RerankMatchedRoute("rewritten-semantic", 3, 0.76d)
                ),
                Map.of("metadata-exact", 1, "rewritten-semantic", 3),
                Map.of("metadata-exact", 0.98d, "rewritten-semantic", 0.76d),
                0.91d
            ),
            "memory:mem-pay-401",
            new RerankRouteEvidence(
                List.of("qv-failure-pattern", "qv-graph"),
                List.of(
                    new RerankMatchedRoute("semantic-memory", 1, 0.84d),
                    new RerankMatchedRoute("graph-memory", 2, 0.86d)
                ),
                Map.of("semantic-memory", 1, "graph-memory", 2),
                Map.of("semantic-memory", 0.84d, "graph-memory", 0.86d),
                0.9d
            )
        );
    }
}
