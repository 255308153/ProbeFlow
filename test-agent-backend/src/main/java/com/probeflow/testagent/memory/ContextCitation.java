package com.probeflow.testagent.memory;

public record ContextCitation(
    String citationType,
    String sourceId,
    String sourceRef,
    Double confidence,
    Double score
) {
}
