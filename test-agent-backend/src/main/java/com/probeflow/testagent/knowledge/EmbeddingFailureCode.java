package com.probeflow.testagent.knowledge;

public enum EmbeddingFailureCode {
    EMPTY_TEXT,
    EMPTY_VECTOR,
    DIMENSION_MISMATCH,
    MISSING_CONFIG,
    TIMEOUT,
    REMOTE_ERROR,
    INVALID_RESPONSE
}
