package com.probeflow.testagent.rerank;

import java.util.List;

public record MemoryConflictAuditEvidence(
    MemoryEvidenceRole role,
    String summary,
    List<String> sourceRefs
) {
    public MemoryConflictAuditEvidence {
        role = role == null ? MemoryEvidenceRole.AUDIT : role;
        if (role != MemoryEvidenceRole.AUDIT && role != MemoryEvidenceRole.CONFLICT) {
            throw new IllegalArgumentException("conflict audit evidence must be marked AUDIT or CONFLICT");
        }
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
