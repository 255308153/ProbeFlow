package com.probeflow.testagent.rerank;

import java.util.Objects;

public record CrossEncoderRerankResult(
    RerankOutput output,
    boolean usedProvider,
    boolean usedFallback,
    CrossEncoderRerankError error
) {
    public CrossEncoderRerankResult {
        output = output == null ? new RerankOutput(null) : output;
        if (usedFallback && error == null) {
            throw new IllegalArgumentException("fallback result must include an error diagnostic");
        }
        if (!usedFallback) {
            Objects.requireNonNull(output, "output must not be null");
        }
    }
}
