package com.probeflow.testagent.llm;

import java.util.Map;

public record LlmRequest(
    String provider,
    String model,
    String purpose,
    String prompt,
    String taskId,
    String planStepId,
    Map<String, Object> metadata
) {

    public LlmRequest {
        provider = required("provider", provider);
        model = required("model", model);
        purpose = required("purpose", purpose);
        prompt = prompt == null ? "" : prompt;
        taskId = clean(taskId);
        planStepId = clean(planStepId);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static LlmRequest of(String provider, String model, String purpose, String prompt) {
        return new LlmRequest(provider, model, purpose, prompt, null, null, Map.of());
    }

    private static String required(String field, String value) {
        var cleaned = clean(value);
        if (cleaned == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return cleaned;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
