package com.probeflow.testagent.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ContextCitation(
    String citationType,
    String sourceId,
    String sourceRef,
    Double confidence,
    Double score,
    Map<String, Object> evidence
) {

    public ContextCitation {
        evidence = evidence == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }

    public ContextCitation(
        String citationType,
        String sourceId,
        String sourceRef,
        Double confidence,
        Double score
    ) {
        this(citationType, sourceId, sourceRef, confidence, score, Map.of());
    }
}
