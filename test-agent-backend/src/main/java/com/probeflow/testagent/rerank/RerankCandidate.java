package com.probeflow.testagent.rerank;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RerankCandidate(
    RerankCorpusType corpusType,
    RerankCandidateIdentity candidateIdentity,
    RerankSourceIdentity sourceIdentity,
    String title,
    String content,
    int beforeRank,
    double fusedScore,
    RerankRouteEvidence routeEvidence,
    RerankFeatureLedger featureLedger,
    Map<String, Object> metadata,
    int budgetEstimateTokens,
    List<String> missingContextHints
) {
    public RerankCandidate {
        Objects.requireNonNull(corpusType, "corpusType must not be null");
        Objects.requireNonNull(candidateIdentity, "candidateIdentity must not be null");
        if (candidateIdentity.corpusType() != corpusType) {
            throw new IllegalArgumentException("candidateIdentity corpusType must match candidate corpusType");
        }
        Objects.requireNonNull(sourceIdentity, "sourceIdentity must not be null");
        routeEvidence = routeEvidence == null ? RerankRouteEvidence.empty() : routeEvidence;
        Objects.requireNonNull(featureLedger, "featureLedger must not be null");
        metadata = metadata == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        budgetEstimateTokens = budgetEstimateTokens > 0 ? budgetEstimateTokens : featureLedger.tokenCost();
        missingContextHints = missingContextHints == null ? List.of() : List.copyOf(missingContextHints);
    }
}
