package com.probeflow.testagent.llm;

public record LlmTokenUsage(
    int promptTokens,
    int completionTokens,
    int totalTokens
) {

    public LlmTokenUsage {
        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token usage cannot be negative");
        }
        if (totalTokens == 0 && (promptTokens > 0 || completionTokens > 0)) {
            totalTokens = promptTokens + completionTokens;
        }
    }

    public static LlmTokenUsage of(int promptTokens, int completionTokens) {
        return new LlmTokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
    }

    public static LlmTokenUsage zero() {
        return new LlmTokenUsage(0, 0, 0);
    }
}
