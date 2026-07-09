package com.probeflow.testagent.memorygraph;

import java.util.List;

public record MemoryGraphRelatedMemory(
    String memoryId,
    String summary,
    String sourceRef,
    String matchReason,
    List<String> relationPath,
    double confidence,
    List<String> sourceMemoryIds,
    List<String> evidenceSummaries
) {
}
