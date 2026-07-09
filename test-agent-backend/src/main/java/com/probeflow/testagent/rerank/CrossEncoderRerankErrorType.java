package com.probeflow.testagent.rerank;

public enum CrossEncoderRerankErrorType {
    TIMEOUT,
    REMOTE_ERROR,
    INVALID_RESPONSE,
    MISSING_SCORE,
    PARTIAL_RESULT
}
