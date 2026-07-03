package com.probeflow.testagent.knowledge;

import java.util.List;

public record KnowledgeRetrievalResult(
    String rawQuery,
    List<KnowledgeRetrievalHit> hits,
    KnowledgeContext knowledgeContext,
    double coverage,
    int totalCandidates,
    int totalTokens,
    boolean lowConfidence
) {

    public boolean isEmpty() {
        return hits.isEmpty();
    }
}
