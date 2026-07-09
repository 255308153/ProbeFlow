package com.probeflow.testagent.memory;

import com.probeflow.testagent.retrieval.RetrievalRouteDiagnostic;
import java.util.List;

public record LongTermMemoryRetrievalResult(
    List<LongTermMemoryRetrievalHit> hits,
    int totalCandidates,
    int totalTokens,
    List<RetrievalRouteDiagnostic> routeDiagnostics
) {

    public LongTermMemoryRetrievalResult {
        routeDiagnostics = routeDiagnostics == null ? List.of() : List.copyOf(routeDiagnostics);
    }

    public LongTermMemoryRetrievalResult(
        List<LongTermMemoryRetrievalHit> hits,
        int totalCandidates,
        int totalTokens
    ) {
        this(hits, totalCandidates, totalTokens, List.of());
    }

    public boolean isEmpty() {
        return hits == null || hits.isEmpty();
    }
}
