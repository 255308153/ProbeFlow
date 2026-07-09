package com.probeflow.testagent.retrieval;

public record RetrievalRouteDiagnostic(
    String routeName,
    String queryVariantId,
    int routeLimit,
    double confidenceGate,
    int candidateCount,
    String diagnostic
) {
}
