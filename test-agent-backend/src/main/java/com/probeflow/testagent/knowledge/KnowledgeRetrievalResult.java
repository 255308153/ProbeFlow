package com.probeflow.testagent.knowledge;

import java.util.List;

public record KnowledgeRetrievalResult(
    String rawQuery,
    List<KnowledgeRetrievalHit> hits,
    KnowledgeContext knowledgeContext,
    double coverage,
    int totalCandidates,
    int totalTokens,
    boolean lowConfidence,
    List<String> diagnostics
) {

    public KnowledgeRetrievalResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public KnowledgeRetrievalResult(
        String rawQuery,
        List<KnowledgeRetrievalHit> hits,
        KnowledgeContext knowledgeContext,
        double coverage,
        int totalCandidates,
        int totalTokens,
        boolean lowConfidence
    ) {
        this(rawQuery, hits, knowledgeContext, coverage, totalCandidates, totalTokens, lowConfidence, List.of());
    }

    public boolean isEmpty() {
        return hits.isEmpty();
    }
}
