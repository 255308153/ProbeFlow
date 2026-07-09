package com.probeflow.testagent.memorygraph;

import java.util.List;

public record MemoryGraphProjectionSummary(
    int processedMemoryCount,
    int createdNodeCount,
    int updatedNodeCount,
    int createdEdgeCount,
    int updatedEdgeCount,
    int skippedMemoryCount,
    List<String> warnings
) {
}
