package com.probeflow.testagent.llm;

public class LlmProviderException extends RuntimeException {

    private final LlmErrorType errorType;
    private final String providerTraceId;

    public LlmProviderException(LlmErrorType errorType, String message) {
        this(errorType, message, null, null);
    }

    public LlmProviderException(LlmErrorType errorType, String message, String providerTraceId, Throwable cause) {
        super(message, cause);
        this.errorType = errorType == null ? LlmErrorType.PROVIDER_ERROR : errorType;
        this.providerTraceId = clean(providerTraceId);
    }

    public LlmErrorType errorType() {
        return errorType;
    }

    public String providerTraceId() {
        return providerTraceId;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
