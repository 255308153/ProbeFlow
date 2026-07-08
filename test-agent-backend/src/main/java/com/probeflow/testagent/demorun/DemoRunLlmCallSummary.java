package com.probeflow.testagent.demorun;

import java.util.Map;

public record DemoRunLlmCallSummary(
    String purpose,
    String status,
    String provider,
    String model,
    int promptTokens,
    int completionTokens,
    int totalTokens,
    String errorType,
    String errorMessage,
    Map<String, Object> metadata
) {

    public DemoRunLlmCallSummary {
        purpose = clean(purpose, "UNKNOWN");
        status = clean(status, "SKIPPED");
        provider = clean(provider, "unknown");
        model = clean(model, "unknown");
        errorType = clean(errorType, "NONE");
        errorMessage = clean(errorMessage, null);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
