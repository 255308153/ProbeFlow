package com.probeflow.testagent.rerank;

public enum LlmRerankErrorType {
    DISABLED,
    INVALID_OUTPUT,
    UNKNOWN_CANDIDATE,
    INVALID_EVIDENCE,
    TIMEOUT,
    REMOTE_ERROR
}
