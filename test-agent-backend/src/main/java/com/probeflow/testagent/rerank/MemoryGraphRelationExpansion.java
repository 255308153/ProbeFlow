package com.probeflow.testagent.rerank;

import java.util.List;

public record MemoryGraphRelationExpansion(
    List<String> relationPath,
    Double relationConfidence,
    List<String> sourceMemoryIds,
    List<String> sourceRefs,
    List<String> factFingerprints,
    String graphEvidenceSummary,
    String explanation
) {
    public MemoryGraphRelationExpansion {
        relationPath = relationPath == null ? List.of() : List.copyOf(relationPath);
        sourceMemoryIds = sourceMemoryIds == null ? List.of() : List.copyOf(sourceMemoryIds);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        factFingerprints = factFingerprints == null ? List.of() : List.copyOf(factFingerprints);
    }
}
