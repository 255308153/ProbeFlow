package com.probeflow.testagent.memory;

public record ContextBudget(
    int requestedTokenBudget,
    int apiTokens,
    int sessionTokens,
    int taskMemoryTokens,
    int knowledgeTokens,
    int longTermMemoryTokens,
    int totalEstimatedTokens,
    int originalEstimatedTokens,
    boolean pruned
) {
}
