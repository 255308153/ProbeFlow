package com.probeflow.testagent.llm;

import java.util.Map;

public record LlmResponse(
    String provider,
    String model,
    String text,
    LlmTokenUsage tokenUsage,
    String providerTraceId,
    boolean fakeProvider,
    Map<String, Object> metadata
) {

    public LlmResponse {
        provider = required("provider", provider);
        model = required("model", model);
        text = text == null ? "" : text;
        tokenUsage = tokenUsage == null ? LlmTokenUsage.zero() : tokenUsage;
        providerTraceId = clean(providerTraceId);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static LlmResponse text(String provider, String model, String text, LlmTokenUsage tokenUsage) {
        return new LlmResponse(provider, model, text, tokenUsage, null, false, Map.of());
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
