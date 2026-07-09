package com.probeflow.testagent.rerank;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record LlmRerankRequest(
    String query,
    List<RerankCandidate> candidates,
    Map<String, Object> metadata
) {
    public LlmRerankRequest {
        query = query == null ? "" : query;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        metadata = metadata == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
