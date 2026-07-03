package com.probeflow.testagent.memory;

import java.util.List;

public record LongTermMemoryRetrievalResult(
    List<LongTermMemoryRetrievalHit> hits,
    int totalCandidates,
    int totalTokens
) {
    public boolean isEmpty() {
        return hits == null || hits.isEmpty();
    }
}
