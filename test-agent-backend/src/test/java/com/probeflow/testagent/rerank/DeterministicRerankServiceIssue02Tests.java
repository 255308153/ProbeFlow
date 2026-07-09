package com.probeflow.testagent.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeterministicRerankServiceIssue02Tests {

    private final DeterministicRerankEngine service = new DeterministicRerankEngine();

    @Test
    void promotesCurrentStageMultiRouteExactEntityCandidateWithExplanation() {
        var oldTop = knowledgeCandidate(
            "old-lexical-note",
            1,
            routes(0.48d, new RerankMatchedRoute("lexical", 1, 0.9d)),
            knowledgeLedger(
                0.18d,
                0.48d,
                0.0d,
                0.87d,
                0.46d,
                0.92d,
                0.60d,
                0.42d,
                false,
                List.of(),
                180
            )
        );
        var filler = knowledgeCandidate(
            "semantic-middle-note",
            2,
            routes(0.55d, new RerankMatchedRoute("semantic", 2, 0.76d)),
            knowledgeLedger(
                0.52d,
                0.55d,
                0.0d,
                0.76d,
                0.53d,
                0.58d,
                0.60d,
                0.52d,
                false,
                List.of(),
                180
            )
        );
        var currentStageExact = knowledgeCandidate(
            "pay-401-current-stage",
            3,
            routes(
                0.82d,
                new RerankMatchedRoute("metadata-exact", 1, 0.97d),
                new RerankMatchedRoute("rewritten-semantic", 2, 0.8d),
                new RerankMatchedRoute("api-path", 3, 0.75d)
            ),
            knowledgeLedger(
                0.96d,
                0.82d,
                1.0d,
                0.78d,
                0.81d,
                0.72d,
                0.80d,
                0.79d,
                false,
                List.of(),
                180
            )
        );

        var output = service.rerank(List.of(oldTop, filler, currentStageExact));

        var first = output.items().getFirst();
        assertThat(first.candidate().candidateIdentity().stableKey()).isEqualTo("knowledge:pay-401-current-stage");
        assertThat(first.beforeRank()).isEqualTo(3);
        assertThat(first.afterRank()).isEqualTo(1);
        assertThat(first.reasons()).contains(
            "stage-fit",
            "route-agreement",
            "exact-entity",
            "semantic-relevance",
            "metadata-relevance",
            "lexical-relevance"
        );
        assertThat(first.penalties()).isEmpty();
        assertThat(first.scoreExplanation()).contains(
            "knowledge:pay-401-current-stage",
            "beforeRank=3",
            "afterRank=1",
            "rerankScore=",
            "reasons=stage-fit,route-agreement,exact-entity"
        );
    }

    @Test
    void appliesKnowledgeAuthorityFreshnessAndVisibleRiskPenalties() {
        var staleCommunityNote = knowledgeCandidate(
            "community-stale-pay-note",
            1,
            routes(0.72d, new RerankMatchedRoute("semantic", 1, 0.83d)),
            knowledgeLedger(
                0.78d,
                0.72d,
                0.0d,
                0.82d,
                0.74d,
                0.71d,
                0.30d,
                0.20d,
                false,
                List.of(),
                220
            )
        );
        var officialFreshGuide = knowledgeCandidate(
            "official-fresh-pay-guide",
            2,
            routes(0.64d, new RerankMatchedRoute("metadata", 2, 0.74d)),
            knowledgeLedger(
                0.79d,
                0.64d,
                0.0d,
                0.73d,
                0.69d,
                0.56d,
                1.0d,
                0.95d,
                false,
                List.of(),
                220
            )
        );
        var riskyBloatedGuide = knowledgeCandidate(
            "risky-bloated-pay-guide",
            3,
            routes(
                0.95d,
                new RerankMatchedRoute("metadata-exact", 1, 0.98d),
                new RerankMatchedRoute("rewritten-semantic", 1, 0.95d),
                new RerankMatchedRoute("lexical", 1, 0.91d)
            ),
            knowledgeLedger(
                0.95d,
                0.95d,
                1.0d,
                0.95d,
                0.95d,
                0.95d,
                1.0d,
                0.95d,
                true,
                List.of("contradicts-official-runbook"),
                2600
            )
        );

        var output = service.rerank(List.of(staleCommunityNote, officialFreshGuide, riskyBloatedGuide));

        assertThat(output.items()).extracting(item -> item.candidate().candidateIdentity().stableKey())
            .containsExactly(
                "knowledge:official-fresh-pay-guide",
                "knowledge:community-stale-pay-note",
                "knowledge:risky-bloated-pay-guide"
            );

        var promoted = output.items().getFirst();
        assertThat(promoted.beforeRank()).isEqualTo(2);
        assertThat(promoted.reasons()).contains("document-authority", "freshness", "stage-fit");

        var risky = output.items().get(2);
        assertThat(risky.penalties()).contains("very-high-token-cost", "low-confidence", "conflict-signals");
        assertThat(risky.scoreExplanation()).contains(
            "penalties=very-high-token-cost,low-confidence,conflict-signals"
        );
    }

    @Test
    void appliesMemoryAndGraphSignalsIncludingPathLength() {
        var weakGraphMemory = memoryCandidate(
            "weak-graph-memory",
            1,
            routes(0.78d, new RerankMatchedRoute("graph-memory", 1, 0.78d)),
            memoryLedger(
                0.82d,
                0.78d,
                0.70d,
                0.76d,
                0.71d,
                0.50d,
                0.72d,
                0.88d,
                0.84d,
                0.80d,
                0.34d,
                5,
                false,
                List.of(),
                180
            )
        );
        var strongGraphMemory = memoryCandidate(
            "strong-graph-memory",
            3,
            routes(
                0.80d,
                new RerankMatchedRoute("semantic-memory", 2, 0.8d),
                new RerankMatchedRoute("graph-memory", 1, 0.9d)
            ),
            memoryLedger(
                0.86d,
                0.80d,
                0.78d,
                0.74d,
                0.76d,
                0.55d,
                0.78d,
                0.93d,
                0.88d,
                0.82d,
                0.91d,
                2,
                false,
                List.of(),
                180
            )
        );

        var output = service.rerank(List.of(weakGraphMemory, strongGraphMemory));

        assertThat(output.items()).extracting(item -> item.candidate().candidateIdentity().stableKey())
            .containsExactly("memory:strong-graph-memory", "memory:weak-graph-memory");
        assertThat(output.items().getFirst().beforeRank()).isEqualTo(3);
        assertThat(output.items().getFirst().reasons()).contains(
            "memory-confidence",
            "memory-importance",
            "memory-success-contribution",
            "graph-confidence",
            "short-graph-path"
        );
        assertThat(output.items().get(1).penalties()).contains("weak-graph-confidence", "long-graph-path");
    }

    @Test
    void keepsStableOrderingAcrossRepeatedRunsAndTieBreaksByBeforeRankThenIdentity() {
        var rankTwo = knowledgeCandidate(
            "same-score-b",
            2,
            routes(0.70d, new RerankMatchedRoute("semantic", 2, 0.70d)),
            tieLedger()
        );
        var rankOne = knowledgeCandidate(
            "same-score-a",
            1,
            routes(0.70d, new RerankMatchedRoute("semantic", 1, 0.70d)),
            tieLedger()
        );
        var duplicateRankEarlierIdentity = knowledgeCandidate(
            "same-score-0",
            2,
            routes(0.70d, new RerankMatchedRoute("semantic", 3, 0.70d)),
            tieLedger()
        );

        var firstRun = service.rerank(List.of(rankTwo, rankOne, duplicateRankEarlierIdentity));
        var secondRun = service.rerank(List.of(rankTwo, rankOne, duplicateRankEarlierIdentity));

        assertThat(firstRun.items()).extracting(item -> item.candidate().candidateIdentity().stableKey())
            .containsExactly("knowledge:same-score-a", "knowledge:same-score-0", "knowledge:same-score-b");
        assertThat(secondRun.items()).extracting(item -> item.candidate().candidateIdentity().stableKey())
            .containsExactlyElementsOf(firstRun.items().stream()
                .map(item -> item.candidate().candidateIdentity().stableKey())
                .toList());
        assertThat(firstRun.items()).extracting(RerankOutputItem::rerankScore)
            .containsExactlyElementsOf(secondRun.items().stream().map(RerankOutputItem::rerankScore).toList());
    }

    private RerankCandidate knowledgeCandidate(
        String id,
        int beforeRank,
        RerankRouteEvidence routes,
        RerankFeatureLedger ledger
    ) {
        return candidate(RerankCorpusType.KNOWLEDGE, id, beforeRank, routes, ledger);
    }

    private RerankCandidate memoryCandidate(
        String id,
        int beforeRank,
        RerankRouteEvidence routes,
        RerankFeatureLedger ledger
    ) {
        return candidate(RerankCorpusType.MEMORY, id, beforeRank, routes, ledger);
    }

    private RerankCandidate candidate(
        RerankCorpusType corpusType,
        String id,
        int beforeRank,
        RerankRouteEvidence routes,
        RerankFeatureLedger ledger
    ) {
        return new RerankCandidate(
            corpusType,
            new RerankCandidateIdentity(corpusType, id),
            new RerankSourceIdentity(corpusType.name(), "fixture/" + id, Map.of("id", id)),
            id,
            "Fixture content for " + id,
            beforeRank,
            routes.fusedScore(),
            routes,
            ledger,
            Map.of(),
            ledger.tokenCost(),
            List.of()
        );
    }

    private RerankRouteEvidence routes(double fusedScore, RerankMatchedRoute... matchedRoutes) {
        var routeList = List.of(matchedRoutes);
        return new RerankRouteEvidence(
            List.of("query-fixture"),
            routeList,
            Map.of(),
            Map.of(),
            fusedScore
        );
    }

    private RerankFeatureLedger knowledgeLedger(
        Double stageFit,
        Double routeAgreement,
        Double exactEntity,
        Double semantic,
        Double metadata,
        Double lexical,
        Double authority,
        Double freshness,
        boolean lowConfidence,
        List<String> conflictSignals,
        int tokenCost
    ) {
        return new RerankFeatureLedger(
            semantic,
            metadata,
            lexical,
            routeAgreement,
            exactEntity,
            stageFit,
            authority,
            freshness,
            null,
            null,
            null,
            null,
            null,
            lowConfidence,
            conflictSignals,
            tokenCost,
            List.of()
        );
    }

    private RerankFeatureLedger memoryLedger(
        Double stageFit,
        Double routeAgreement,
        Double exactEntity,
        Double semantic,
        Double metadata,
        Double lexical,
        Double freshness,
        Double memoryConfidence,
        Double memoryImportance,
        Double memorySuccessContribution,
        Double graphConfidence,
        Integer graphPathLength,
        boolean lowConfidence,
        List<String> conflictSignals,
        int tokenCost
    ) {
        return new RerankFeatureLedger(
            semantic,
            metadata,
            lexical,
            routeAgreement,
            exactEntity,
            stageFit,
            null,
            freshness,
            memoryConfidence,
            memoryImportance,
            memorySuccessContribution,
            graphConfidence,
            graphPathLength,
            lowConfidence,
            conflictSignals,
            tokenCost,
            List.of()
        );
    }

    private RerankFeatureLedger tieLedger() {
        return knowledgeLedger(
            0.70d,
            0.70d,
            0.0d,
            0.70d,
            0.70d,
            0.70d,
            0.70d,
            0.70d,
            false,
            List.of(),
            220
        );
    }
}
