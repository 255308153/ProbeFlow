package com.probeflow.testagent.llm;

public class ManualRealLlmClientException extends RuntimeException {

    private final LlmErrorType errorType;
    private final String providerTraceId;

    public ManualRealLlmClientException(LlmErrorType errorType, String message) {
        this(errorType, message, null, null);
    }

    public ManualRealLlmClientException(
        LlmErrorType errorType,
        String message,
        String providerTraceId,
        Throwable cause
    ) {
        super(message, cause);
        this.errorType = errorType == null ? LlmErrorType.PROVIDER_ERROR : errorType;
        this.providerTraceId = providerTraceId;
    }

    public LlmErrorType errorType() {
        return errorType;
    }

    public String providerTraceId() {
        return providerTraceId;
    }
}
