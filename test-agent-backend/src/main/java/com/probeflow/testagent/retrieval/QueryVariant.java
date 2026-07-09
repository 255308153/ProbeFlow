package com.probeflow.testagent.retrieval;

public record QueryVariant(
    String deterministicId,
    String queryText,
    QueryIntent intent,
    QueryTargetCorpus targetCorpus,
    String stageProfile,
    QueryFilters filters,
    int priority,
    String reason
) {
}
