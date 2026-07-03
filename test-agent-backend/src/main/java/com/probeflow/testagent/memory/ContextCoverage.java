package com.probeflow.testagent.memory;

public record ContextCoverage(
    boolean hasApiContext,
    boolean hasTaskState,
    boolean hasSessionContext,
    boolean hasTaskMemory,
    boolean hasKnowledgeContext,
    boolean hasLongTermMemory,
    double knowledgeCoverage,
    boolean lowConfidence
) {
}
