package com.probeflow.testagent.rerank;

import java.util.List;

public record KnowledgeExpansionResult(
    List<KnowledgeExpansionContext> contexts,
    int requestedTokenBudget,
    int totalTokens,
    boolean pruned
) {
    public KnowledgeExpansionResult {
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
        requestedTokenBudget = Math.max(0, requestedTokenBudget);
        totalTokens = Math.max(0, totalTokens);
    }
}
