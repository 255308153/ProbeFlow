package com.probeflow.testagent.rerank;

import java.util.List;
import java.util.Objects;

public record MemoryEvidenceAnchor(
    MemoryEvidenceAnchorType anchorType,
    String memoryAnchor,
    String factFingerprint,
    List<String> graphRelationPath
) {
    public MemoryEvidenceAnchor {
        Objects.requireNonNull(anchorType, "anchorType must not be null");
        Objects.requireNonNull(memoryAnchor, "memoryAnchor must not be null");
        graphRelationPath = graphRelationPath == null ? List.of() : List.copyOf(graphRelationPath);
    }
}
