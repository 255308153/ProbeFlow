package com.probeflow.testagent.rerank;

public record CrossEncoderRerankProviderResult(
    String candidateId,
    Double modelScore,
    Integer rank,
    String reason
) {
    public CrossEncoderRerankProviderResult {
        reason = reason == null ? "" : reason.trim();
    }
}
