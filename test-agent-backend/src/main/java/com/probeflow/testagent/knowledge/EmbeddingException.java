package com.probeflow.testagent.knowledge;

public class EmbeddingException extends RuntimeException {

    private final EmbeddingFailureCode failureCode;
    private final String profileId;

    public EmbeddingException(EmbeddingFailureCode failureCode, String profileId, String message) {
        super(message);
        this.failureCode = failureCode;
        this.profileId = profileId;
    }

    public EmbeddingFailureCode getFailureCode() {
        return failureCode;
    }

    public String getProfileId() {
        return profileId;
    }
}
