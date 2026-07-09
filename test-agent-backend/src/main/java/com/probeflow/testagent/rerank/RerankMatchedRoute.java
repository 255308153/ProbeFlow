package com.probeflow.testagent.rerank;

import java.util.Objects;

public record RerankMatchedRoute(
    String routeName,
    int rank,
    double score
) {
    public RerankMatchedRoute {
        Objects.requireNonNull(routeName, "routeName must not be null");
    }
}
