package com.probeflow.testagent.memorygraph;

import java.util.List;

public record MemoryGraphAuditRelation(
    MemoryGraphRelationType relationType,
    String sourceNodeId,
    String targetNodeId,
    double confidence,
    List<String> sourceMemoryIds,
    List<String> sourceRefs,
    List<String> factFingerprints,
    List<String> evidenceSummaries
) {
}
