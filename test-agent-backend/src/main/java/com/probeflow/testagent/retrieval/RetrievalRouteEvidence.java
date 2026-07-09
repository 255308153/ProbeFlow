package com.probeflow.testagent.retrieval;

import java.util.LinkedHashMap;
import java.util.Map;

public record RetrievalRouteEvidence(
    String routeName,
    String queryVariantId,
    String queryIntent,
    int routeRank,
    double routeScore,
    String matchReason,
    Map<String, Object> sourceEvidence
) {

    public RetrievalRouteEvidence(
        String routeName,
        String queryVariantId,
        int routeRank,
        double routeScore,
        String matchReason,
        Map<String, Object> sourceEvidence
    ) {
        this(routeName, queryVariantId, null, routeRank, routeScore, matchReason, sourceEvidence);
    }

    public RetrievalRouteEvidence {
        queryIntent = queryIntent == null ? null : queryIntent.trim();
        sourceEvidence = sourceEvidence == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(sourceEvidence));
    }
}
