package com.probeflow.testagent.rerank;

import java.util.Objects;

public record CrossEncoderRerankError(
    CrossEncoderRerankErrorType type,
    String diagnostic
) {
    public CrossEncoderRerankError {
        Objects.requireNonNull(type, "type must not be null");
        diagnostic = diagnostic == null || diagnostic.isBlank()
            ? "Cross Encoder rerank failed; deterministic fallback used."
            : diagnostic.trim();
    }
}
