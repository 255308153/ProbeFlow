package com.probeflow.testagent.rerank;

import java.util.Objects;

public record KnowledgeExpansionCitation(
    String sourceChunkId,
    String sourceRevision,
    String sourceRef,
    KnowledgeExpansionCitationRole role
) {
    public KnowledgeExpansionCitation {
        Objects.requireNonNull(sourceChunkId, "sourceChunkId must not be null");
        Objects.requireNonNull(sourceRevision, "sourceRevision must not be null");
        sourceRef = sourceRef == null ? "" : sourceRef;
        role = role == null ? KnowledgeExpansionCitationRole.EXPANDED_CONTEXT : role;
    }
}
