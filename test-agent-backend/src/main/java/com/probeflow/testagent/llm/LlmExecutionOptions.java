package com.probeflow.testagent.llm;

public record LlmExecutionOptions(
    String provider,
    String model,
    Double temperature,
    Integer maxTokens,
    Integer timeoutMs,
    Integer retryAttempts
) {

    public LlmExecutionOptions {
        provider = clean(provider);
        model = clean(model);
        if (temperature != null && (temperature < 0.0d || temperature > 2.0d)) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (maxTokens != null && maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (timeoutMs != null && timeoutMs < 1) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        if (retryAttempts != null && retryAttempts < 0) {
            throw new IllegalArgumentException("retryAttempts must be non-negative");
        }
    }

    public static LlmExecutionOptions of(String provider, String model) {
        return new LlmExecutionOptions(provider, model, null, null, null, null);
    }

    public LlmExecutionOptions withFallbacks(String fallbackProvider, String fallbackModel) {
        return new LlmExecutionOptions(
            provider == null ? fallbackProvider : provider,
            model == null ? fallbackModel : model,
            temperature,
            maxTokens,
            timeoutMs,
            retryAttempts
        );
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
