package com.probeflow.testagent.knowledge;

import java.util.Objects;

public record KnowledgeRouteEvidence(
    String routeName,
    String queryVariantId,
    String queryIntent,
    int routeRank,
    double routeScore,
    String matchReason
) {

    public KnowledgeRouteEvidence(
        String routeName,
        String queryVariantId,
        int routeRank,
        double routeScore,
        String matchReason
    ) {
        this(routeName, queryVariantId, null, routeRank, routeScore, matchReason);
    }

    public KnowledgeRouteEvidence {
        Objects.requireNonNull(routeName, "routeName must not be null");
        Objects.requireNonNull(queryVariantId, "queryVariantId must not be null");
        queryIntent = queryIntent == null ? null : queryIntent.trim();
        if (routeRank <= 0) {
            throw new IllegalArgumentException("routeRank must be positive");
        }
        if (Double.isNaN(routeScore) || Double.isInfinite(routeScore)) {
            throw new IllegalArgumentException("routeScore must be finite");
        }
        matchReason = matchReason == null ? "" : matchReason.trim();
    }
}
