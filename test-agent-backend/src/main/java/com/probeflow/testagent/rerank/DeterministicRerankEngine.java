package com.probeflow.testagent.rerank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DeterministicRerankEngine {

    private static final double STAGE_FIT_WEIGHT = 0.16d;
    private static final double ROUTE_AGREEMENT_WEIGHT = 0.09d;
    private static final double ROUTE_DIVERSITY_WEIGHT = 0.05d;
    private static final double EXACT_ENTITY_WEIGHT = 0.13d;
    private static final double SEMANTIC_WEIGHT = 0.11d;
    private static final double METADATA_WEIGHT = 0.07d;
    private static final double LEXICAL_WEIGHT = 0.04d;
    private static final double DOCUMENT_AUTHORITY_WEIGHT = 0.08d;
    private static final double KNOWLEDGE_FRESHNESS_WEIGHT = 0.05d;
    private static final double MEMORY_FRESHNESS_WEIGHT = 0.03d;
    private static final double MEMORY_CONFIDENCE_WEIGHT = 0.07d;
    private static final double MEMORY_IMPORTANCE_WEIGHT = 0.05d;
    private static final double MEMORY_SUCCESS_WEIGHT = 0.05d;
    private static final double GRAPH_CONFIDENCE_WEIGHT = 0.05d;
    private static final double GRAPH_PATH_WEIGHT = 0.03d;
    private static final double BUDGET_EFFICIENT_BONUS = 0.02d;
    private static final int BUDGET_EFFICIENT_TOKEN_LIMIT = 256;
    private static final int HIGH_TOKEN_COST_LIMIT = 800;
    private static final int VERY_HIGH_TOKEN_COST_LIMIT = 1500;

    private final RerankCandidateContractService contractService;

    public DeterministicRerankEngine() {
        this(new RerankCandidateContractService());
    }

    DeterministicRerankEngine(RerankCandidateContractService contractService) {
        this.contractService = contractService;
    }

    public RerankOutput rerank(List<RerankCandidate> candidates) {
        var safeCandidates = candidates == null ? List.<RerankCandidate>of() : candidates;
        var assignments = safeCandidates.stream()
            .map(this::score)
            .sorted(Comparator
                .comparingDouble(ScoredCandidate::score).reversed()
                .thenComparingInt(scored -> effectiveBeforeRank(scored.candidate()))
                .thenComparing(scored -> scored.candidate().candidateIdentity().stableKey()))
            .map(scored -> new RerankScoreAssignment(
                scored.candidate().candidateIdentity(),
                scored.score(),
                scored.reasons(),
                scored.penalties()
            ))
            .toList();

        return contractService.recordOutput(safeCandidates, assignments);
    }

    private ScoredCandidate score(RerankCandidate candidate) {
        var ledger = candidate.featureLedger();
        var reasons = new ArrayList<String>();
        var penalties = new ArrayList<String>();
        var score = 0.0d;

        score += positiveFeature(ledger.stageFit(), STAGE_FIT_WEIGHT, "stage-fit", reasons);
        if (ledger.stageFit() != null && ledger.stageFit() < 0.35d) {
            penalties.add("low-stage-fit");
            score -= 0.05d;
        }

        score += positiveFeature(ledger.routeAgreement(), ROUTE_AGREEMENT_WEIGHT, "route-agreement", reasons);
        score += routeDiversityScore(candidate.routeEvidence().matchedRoutes().size(), reasons);
        score += positiveFeature(ledger.exactEntity(), EXACT_ENTITY_WEIGHT, "exact-entity", reasons);
        score += positiveFeature(ledger.semantic(), SEMANTIC_WEIGHT, "semantic-relevance", reasons);
        score += positiveFeature(ledger.metadata(), METADATA_WEIGHT, "metadata-relevance", reasons);
        score += positiveFeature(ledger.lexical(), LEXICAL_WEIGHT, "lexical-relevance", reasons);

        if (candidate.corpusType() == RerankCorpusType.KNOWLEDGE) {
            score += positiveFeature(ledger.authority(), DOCUMENT_AUTHORITY_WEIGHT, "document-authority", reasons);
            score += positiveFeature(ledger.freshness(), KNOWLEDGE_FRESHNESS_WEIGHT, "freshness", reasons);
        }

        if (candidate.corpusType() == RerankCorpusType.MEMORY) {
            score += positiveFeature(ledger.freshness(), MEMORY_FRESHNESS_WEIGHT, "freshness", reasons);
            score += positiveFeature(ledger.memoryConfidence(), MEMORY_CONFIDENCE_WEIGHT, "memory-confidence", reasons);
            score += positiveFeature(ledger.memoryImportance(), MEMORY_IMPORTANCE_WEIGHT, "memory-importance", reasons);
            score += positiveFeature(
                ledger.memorySuccessContribution(),
                MEMORY_SUCCESS_WEIGHT,
                "memory-success-contribution",
                reasons
            );
        }

        score += graphScore(ledger, reasons, penalties);
        score += tokenBudgetScore(ledger.tokenCost(), reasons, penalties);
        score -= lowConfidencePenalty(ledger, penalties);
        score -= conflictPenalty(ledger, penalties);

        return new ScoredCandidate(candidate, roundScore(clamp(score)), reasons, penalties);
    }

    private double positiveFeature(Double value, double weight, String reason, List<String> reasons) {
        if (value == null) {
            return 0.0d;
        }
        var normalized = clamp(value);
        if (normalized >= 0.70d) {
            reasons.add(reason);
        }
        return normalized * weight;
    }

    private double routeDiversityScore(int matchedRouteCount, List<String> reasons) {
        if (matchedRouteCount <= 1) {
            return 0.0d;
        }
        if (!reasons.contains("route-agreement")) {
            reasons.add("route-agreement");
        }
        return Math.min(1.0d, matchedRouteCount / 3.0d) * ROUTE_DIVERSITY_WEIGHT;
    }

    private double graphScore(
        RerankFeatureLedger ledger,
        List<String> reasons,
        List<String> penalties
    ) {
        var score = 0.0d;
        if (ledger.graphConfidence() != null) {
            var graphConfidence = clamp(ledger.graphConfidence());
            if (graphConfidence >= 0.70d) {
                reasons.add("graph-confidence");
            }
            if (graphConfidence < 0.45d) {
                penalties.add("weak-graph-confidence");
                score -= 0.08d;
            }
            score += graphConfidence * GRAPH_CONFIDENCE_WEIGHT;
        }

        if (ledger.graphPathLength() != null) {
            var pathScore = graphPathScore(ledger.graphPathLength());
            if (ledger.graphPathLength() <= 2) {
                reasons.add("short-graph-path");
            }
            if (ledger.graphPathLength() > 3) {
                penalties.add("long-graph-path");
                score -= Math.min(0.09d, (ledger.graphPathLength() - 3) * 0.03d);
            }
            score += pathScore * GRAPH_PATH_WEIGHT;
        }
        return score;
    }

    private double tokenBudgetScore(int tokenCost, List<String> reasons, List<String> penalties) {
        if (tokenCost <= 0) {
            return 0.0d;
        }
        if (tokenCost <= BUDGET_EFFICIENT_TOKEN_LIMIT) {
            reasons.add("budget-efficient");
            return BUDGET_EFFICIENT_BONUS;
        }

        var penalty = tokenCostPenalty(tokenCost);
        if (penalty > 0.0d) {
            penalties.add(tokenCost >= VERY_HIGH_TOKEN_COST_LIMIT ? "very-high-token-cost" : "high-token-cost");
        }
        return -penalty;
    }

    private double tokenCostPenalty(int tokenCost) {
        if (tokenCost <= BUDGET_EFFICIENT_TOKEN_LIMIT) {
            return 0.0d;
        }
        if (tokenCost <= HIGH_TOKEN_COST_LIMIT) {
            var range = HIGH_TOKEN_COST_LIMIT - BUDGET_EFFICIENT_TOKEN_LIMIT;
            return ((double) tokenCost - BUDGET_EFFICIENT_TOKEN_LIMIT) / range * 0.02d;
        }
        var overHighLimit = Math.min(2400, tokenCost - HIGH_TOKEN_COST_LIMIT);
        return 0.02d + overHighLimit / 2400.0d * 0.16d;
    }

    private double lowConfidencePenalty(RerankFeatureLedger ledger, List<String> penalties) {
        if (!ledger.lowConfidence()) {
            return 0.0d;
        }
        penalties.add("low-confidence");
        return 0.14d;
    }

    private double conflictPenalty(RerankFeatureLedger ledger, List<String> penalties) {
        if (ledger.conflictSignals().isEmpty()) {
            return 0.0d;
        }
        penalties.add("conflict-signals");
        return 0.18d + Math.min(0.06d, ledger.conflictSignals().size() * 0.02d);
    }

    private double graphPathScore(int pathLength) {
        if (pathLength <= 1) {
            return 1.0d;
        }
        if (pathLength == 2) {
            return 0.85d;
        }
        if (pathLength == 3) {
            return 0.65d;
        }
        return Math.max(0.0d, 0.65d - (pathLength - 3) * 0.20d);
    }

    private int effectiveBeforeRank(RerankCandidate candidate) {
        return candidate.beforeRank() > 0 ? candidate.beforeRank() : Integer.MAX_VALUE;
    }

    private double roundScore(double score) {
        return Math.round(score * 10_000.0d) / 10_000.0d;
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private record ScoredCandidate(
        RerankCandidate candidate,
        double score,
        List<String> reasons,
        List<String> penalties
    ) {
    }
}
