package com.probeflow.testagent.knowledge;

public class ManualRealEmbeddingClientException extends RuntimeException {

    private final EmbeddingFailureCode failureCode;
    private final String providerTraceId;

    public ManualRealEmbeddingClientException(EmbeddingFailureCode failureCode, String message) {
        this(failureCode, message, null, null);
    }

    public ManualRealEmbeddingClientException(
        EmbeddingFailureCode failureCode,
        String message,
        String providerTraceId,
        Throwable cause
    ) {
        super(message, cause);
        this.failureCode = failureCode == null ? EmbeddingFailureCode.REMOTE_ERROR : failureCode;
        this.providerTraceId = providerTraceId;
    }

    public EmbeddingFailureCode failureCode() {
        return failureCode;
    }

    public String providerTraceId() {
        return providerTraceId;
    }
}
