package com.probeflow.testagent.memorygraph;

public record MemoryGraphSeed(
    MemoryGraphEntityType entityType,
    String value,
    String scope
) {
}
