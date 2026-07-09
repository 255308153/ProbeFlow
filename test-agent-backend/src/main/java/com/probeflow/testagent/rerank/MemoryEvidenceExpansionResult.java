package com.probeflow.testagent.rerank;

import java.util.List;

public record MemoryEvidenceExpansionResult(
    List<MemoryEvidenceExpansionItem> items,
    int requestedTokenBudget,
    int totalEstimatedTokens,
    boolean pruned,
    List<String> pruningReasons
) {
    public MemoryEvidenceExpansionResult {
        items = items == null ? List.of() : List.copyOf(items);
        requestedTokenBudget = Math.max(0, requestedTokenBudget);
        totalEstimatedTokens = Math.max(0, totalEstimatedTokens);
        pruningReasons = pruningReasons == null ? List.of() : List.copyOf(pruningReasons);
    }
}
