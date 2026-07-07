package com.probeflow.testagent.businessflowdiscovery;

import java.util.LinkedHashMap;
import java.util.Map;

public record BusinessFlowSourceCoverage(
    int apiSpecEvidenceCount,
    int knowledgeEvidenceCount,
    int memoryEvidenceCount,
    int userSelectionEvidenceCount,
    int llmSuggestionEvidenceCount,
    Map<String, Object> metadata
) {

    public BusinessFlowSourceCoverage {
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
