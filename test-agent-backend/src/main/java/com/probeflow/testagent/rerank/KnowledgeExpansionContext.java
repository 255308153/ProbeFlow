package com.probeflow.testagent.rerank;

import java.util.List;
import java.util.Objects;

public record KnowledgeExpansionContext(
    String anchorChunkId,
    String parentIdentity,
    KnowledgeExpansionReason expansionReason,
    String sourceRevision,
    int tokenCost,
    List<KnowledgeExpansionSource> sources,
    List<KnowledgeExpansionPruning> pruningReasons,
    List<KnowledgeExpansionCitation> citations
) {
    public KnowledgeExpansionContext {
        Objects.requireNonNull(anchorChunkId, "anchorChunkId must not be null");
        parentIdentity = parentIdentity == null ? anchorChunkId : parentIdentity;
        Objects.requireNonNull(expansionReason, "expansionReason must not be null");
        Objects.requireNonNull(sourceRevision, "sourceRevision must not be null");
        tokenCost = Math.max(0, tokenCost);
        sources = sources == null ? List.of() : List.copyOf(sources);
        pruningReasons = pruningReasons == null ? List.of() : List.copyOf(pruningReasons);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
