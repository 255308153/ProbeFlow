package com.probeflow.testagent.llm;

import java.util.Map;

public record ManualRealLlmClientRequest(
    String endpoint,
    String apiKey,
    String model,
    String purpose,
    String prompt,
    int timeoutMs,
    int maxTokens,
    Map<String, Object> metadata
) {

    public ManualRealLlmClientRequest {
        endpoint = clean(endpoint);
        apiKey = clean(apiKey);
        model = clean(model);
        purpose = clean(purpose);
        prompt = prompt == null ? "" : prompt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
