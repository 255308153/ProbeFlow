package com.probeflow.testagent.rerank;

import java.util.List;

public record CrossEncoderRerankProviderRequest(
    String query,
    List<CrossEncoderRerankProviderCandidate> candidates
) {
    public CrossEncoderRerankProviderRequest {
        query = query == null ? "" : query;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
