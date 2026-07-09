package com.probeflow.testagent.rerank;

import java.util.List;

public record KnowledgeExpansionRequest(
    List<RerankOutputItem> rerankedItems,
    List<KnowledgeExpansionSource> sources,
    int tokenBudget
) {
    public KnowledgeExpansionRequest {
        rerankedItems = rerankedItems == null ? List.of() : List.copyOf(rerankedItems);
        sources = sources == null ? List.of() : List.copyOf(sources);
        tokenBudget = Math.max(0, tokenBudget);
    }
}
