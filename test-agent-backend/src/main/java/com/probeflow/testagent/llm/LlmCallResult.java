package com.probeflow.testagent.llm;

public record LlmCallResult(
    LlmCallStatus status,
    LlmResponse response,
    LlmErrorType errorType,
    String errorMessage,
    boolean fakeProvider
) {

    public LlmCallResult {
        status = status == null ? LlmCallStatus.FAILED : status;
        errorType = errorType == null ? LlmErrorType.NONE : errorType;
        errorMessage = clean(errorMessage);
        fakeProvider = fakeProvider || (response != null && response.fakeProvider());
        if (status == LlmCallStatus.SUCCESS && response == null) {
            throw new IllegalArgumentException("successful LLM call result requires a response");
        }
        if (status == LlmCallStatus.SUCCESS && errorType != LlmErrorType.NONE) {
            throw new IllegalArgumentException("successful LLM call result cannot include an error type");
        }
    }

    public static LlmCallResult success(LlmResponse response) {
        return new LlmCallResult(LlmCallStatus.SUCCESS, response, LlmErrorType.NONE, null, response.fakeProvider());
    }

    public static LlmCallResult failure(LlmErrorType errorType, String errorMessage) {
        return new LlmCallResult(LlmCallStatus.FAILED, null, errorType, errorMessage, false);
    }

    public static LlmCallResult blocked(LlmErrorType errorType, String errorMessage) {
        return new LlmCallResult(LlmCallStatus.BLOCKED, null, errorType, errorMessage, false);
    }

    public static LlmCallResult skipped(String reason) {
        return new LlmCallResult(LlmCallStatus.SKIPPED, null, LlmErrorType.NONE, reason, false);
    }

    public boolean succeeded() {
        return status == LlmCallStatus.SUCCESS;
    }

    public boolean failed() {
        return status == LlmCallStatus.FAILED;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
