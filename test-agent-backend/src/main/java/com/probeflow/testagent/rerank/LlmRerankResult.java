package com.probeflow.testagent.rerank;

import java.util.Objects;

public record LlmRerankResult(
    RerankOutput output,
    boolean usedProvider,
    boolean usedFallback,
    LlmRerankError error
) {
    public LlmRerankResult {
        output = output == null ? new RerankOutput(null) : output;
        if (usedFallback && error == null) {
            throw new IllegalArgumentException("fallback result must include an error diagnostic");
        }
        if (!usedFallback) {
            Objects.requireNonNull(output, "output must not be null");
        }
    }
}
