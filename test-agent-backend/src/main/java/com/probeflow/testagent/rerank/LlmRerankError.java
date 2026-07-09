package com.probeflow.testagent.rerank;

import java.util.Objects;

public record LlmRerankError(
    LlmRerankErrorType type,
    String diagnostic
) {
    public LlmRerankError {
        Objects.requireNonNull(type, "type must not be null");
        diagnostic = diagnostic == null || diagnostic.isBlank()
            ? "LLM rerank failed; deterministic fallback used."
            : diagnostic.trim();
    }
}
