package com.probeflow.testagent.rerank;

import java.util.List;

public record RerankScoreAssignment(
    RerankCandidateIdentity candidateIdentity,
    double rerankScore,
    List<String> reasons,
    List<String> penalties
) {
    public RerankScoreAssignment {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        penalties = penalties == null ? List.of() : List.copyOf(penalties);
    }
}
