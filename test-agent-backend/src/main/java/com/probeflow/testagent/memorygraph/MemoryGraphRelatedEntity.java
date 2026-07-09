package com.probeflow.testagent.memorygraph;

import java.util.List;

public record MemoryGraphRelatedEntity(
    MemoryGraphEntityType entityType,
    String displayValue,
    String normalizedValue,
    String scope,
    String matchReason,
    List<String> relationPath,
    double confidence,
    List<String> sourceMemoryIds,
    List<String> evidenceSummaries
) {
}
