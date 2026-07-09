package com.probeflow.testagent.rerank;

import java.util.List;

public record RerankOutputItem(
    RerankCandidate candidate,
    int beforeRank,
    int afterRank,
    double rerankScore,
    String scoreExplanation,
    List<String> reasons,
    List<String> penalties
) {
    public RerankOutputItem {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        penalties = penalties == null ? List.of() : List.copyOf(penalties);
    }
}
