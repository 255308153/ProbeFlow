package com.probeflow.testagent.rerank;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record RerankSourceIdentity(
    String sourceType,
    String sourceRef,
    Map<String, String> sourceIds
) {
    public RerankSourceIdentity {
        sourceIds = sourceIds == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(sourceIds));
    }
}
