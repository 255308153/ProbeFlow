package com.probeflow.testagent.rerank;

import java.util.List;

public record MemoryEvidenceExpansionRequest(
    List<RerankOutputItem> rerankedItems,
    int tokenBudget
) {
    public MemoryEvidenceExpansionRequest {
        rerankedItems = rerankedItems == null ? List.of() : List.copyOf(rerankedItems);
        tokenBudget = Math.max(0, tokenBudget);
    }

    public static MemoryEvidenceExpansionRequest from(RerankOutput output, int tokenBudget) {
        return new MemoryEvidenceExpansionRequest(output == null ? List.of() : output.items(), tokenBudget);
    }
}
