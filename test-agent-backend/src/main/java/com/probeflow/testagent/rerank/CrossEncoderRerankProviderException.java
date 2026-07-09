package com.probeflow.testagent.rerank;

import java.util.Objects;

public class CrossEncoderRerankProviderException extends RuntimeException {

    private final CrossEncoderRerankErrorType type;

    public CrossEncoderRerankProviderException(CrossEncoderRerankErrorType type, String message) {
        super(message);
        this.type = Objects.requireNonNull(type, "type must not be null");
    }

    public CrossEncoderRerankProviderException(
        CrossEncoderRerankErrorType type,
        String message,
        Throwable cause
    ) {
        super(message, cause);
        this.type = Objects.requireNonNull(type, "type must not be null");
    }

    public CrossEncoderRerankErrorType type() {
        return type;
    }
}
