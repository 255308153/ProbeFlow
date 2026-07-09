package com.probeflow.testagent.rerank;

import java.util.Objects;

public record KnowledgeExpansionPruning(
    String sourceChunkId,
    KnowledgeExpansionPruningReason reason
) {
    public KnowledgeExpansionPruning {
        Objects.requireNonNull(sourceChunkId, "sourceChunkId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
