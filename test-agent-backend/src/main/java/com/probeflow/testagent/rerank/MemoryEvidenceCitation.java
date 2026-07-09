package com.probeflow.testagent.rerank;

import java.util.Objects;

public record MemoryEvidenceCitation(
    String memoryAnchor,
    String factFingerprint,
    String evidenceSource,
    MemoryEvidenceRole role
) {
    public MemoryEvidenceCitation {
        Objects.requireNonNull(memoryAnchor, "memoryAnchor must not be null");
        factFingerprint = hasText(factFingerprint) ? factFingerprint : "missing-fact-fingerprint";
        evidenceSource = hasText(evidenceSource) ? evidenceSource : "missing-evidence-source";
        role = role == null ? MemoryEvidenceRole.SUPPORTING : role;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
