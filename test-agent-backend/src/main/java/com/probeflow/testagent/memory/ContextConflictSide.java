package com.probeflow.testagent.memory;

public record ContextConflictSide(
    String sourceType,
    String sourceId,
    String sourceRef,
    Double confidence,
    Double score,
    String summary,
    String detail
) {
}
