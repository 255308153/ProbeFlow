package com.probeflow.testagent.retrieval;

import java.util.LinkedHashMap;
import java.util.Map;

public record RetrievalRouteEvidence(
    String routeName,
    String queryVariantId,
    int routeRank,
    double routeScore,
    String matchReason,
    Map<String, Object> sourceEvidence
) {

    public RetrievalRouteEvidence {
        sourceEvidence = sourceEvidence == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(sourceEvidence));
    }
}
