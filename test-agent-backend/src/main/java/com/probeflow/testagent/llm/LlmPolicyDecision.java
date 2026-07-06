package com.probeflow.testagent.llm;

public record LlmPolicyDecision(
    boolean allowed,
    LlmErrorType errorType,
    String message
) {

    public LlmPolicyDecision {
        errorType = errorType == null ? LlmErrorType.NONE : errorType;
        message = clean(message);
    }

    public static LlmPolicyDecision allow() {
        return new LlmPolicyDecision(true, LlmErrorType.NONE, null);
    }

    public static LlmPolicyDecision block(String message) {
        return new LlmPolicyDecision(false, LlmErrorType.POLICY_BLOCKED, message);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
