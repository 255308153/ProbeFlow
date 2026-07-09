package com.probeflow.testagent.rerank;

import java.util.Locale;
import java.util.Objects;

public record RerankCandidateIdentity(
    RerankCorpusType corpusType,
    String candidateId
) {
    public RerankCandidateIdentity {
        Objects.requireNonNull(corpusType, "corpusType must not be null");
        Objects.requireNonNull(candidateId, "candidateId must not be null");
    }

    public String stableKey() {
        return stableKey(corpusType, candidateId);
    }

    public static String stableKey(RerankCorpusType corpusType, String candidateId) {
        Objects.requireNonNull(corpusType, "corpusType must not be null");
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        return corpusType.name().toLowerCase(Locale.ROOT) + ":" + candidateId;
    }
}
