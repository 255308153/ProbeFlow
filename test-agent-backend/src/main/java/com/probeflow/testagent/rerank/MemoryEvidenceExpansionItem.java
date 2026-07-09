package com.probeflow.testagent.rerank;

import java.util.List;
import java.util.Objects;

public record MemoryEvidenceExpansionItem(
    MemoryEvidenceAnchor anchor,
    String title,
    String fullContent,
    List<String> evidenceSummaries,
    List<String> sourceRefs,
    List<String> mergedSourceRefs,
    int evidenceCount,
    MemoryEvidenceIdentityHints identityHints,
    MemoryGraphRelationExpansion graphRelation,
    List<MemoryConflictAuditEvidence> conflictAudit,
    boolean positiveRecommendationEligible,
    boolean lowConfidence,
    String lowConfidenceReason,
    int estimatedTokens,
    List<String> pruningReasons,
    List<MemoryEvidenceCitation> citations
) {
    public MemoryEvidenceExpansionItem {
        Objects.requireNonNull(anchor, "anchor must not be null");
        evidenceSummaries = evidenceSummaries == null ? List.of() : List.copyOf(evidenceSummaries);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        mergedSourceRefs = mergedSourceRefs == null ? List.of() : List.copyOf(mergedSourceRefs);
        identityHints = identityHints == null
            ? new MemoryEvidenceIdentityHints(null, null, null, null, null, null, null, null)
            : identityHints;
        conflictAudit = conflictAudit == null ? List.of() : List.copyOf(conflictAudit);
        estimatedTokens = Math.max(0, estimatedTokens);
        pruningReasons = pruningReasons == null ? List.of() : List.copyOf(pruningReasons);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
