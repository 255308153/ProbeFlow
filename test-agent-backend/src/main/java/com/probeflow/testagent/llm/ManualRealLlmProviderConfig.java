package com.probeflow.testagent.llm;

import java.util.ArrayList;
import java.util.List;

public record ManualRealLlmProviderConfig(
    String providerName,
    String endpoint,
    String apiKey,
    String model,
    Integer timeoutMs,
    Integer maxTokens,
    Integer costLimitCents
) {

    public static final String DEFAULT_PROVIDER_NAME = "manual-real";

    public ManualRealLlmProviderConfig {
        providerName = clean(providerName, DEFAULT_PROVIDER_NAME);
        endpoint = clean(endpoint, null);
        apiKey = clean(apiKey, null);
        model = clean(model, null);
    }

    public List<String> missingRequiredFields() {
        var missing = new ArrayList<String>();
        if (endpoint == null) {
            missing.add("endpoint");
        }
        if (apiKey == null) {
            missing.add("key");
        }
        if (model == null) {
            missing.add("model");
        }
        if (timeoutMs == null || timeoutMs < 1) {
            missing.add("timeoutMs");
        }
        if (maxTokens == null || maxTokens < 1) {
            missing.add("maxTokens");
        }
        if (costLimitCents == null || costLimitCents < 1) {
            missing.add("costLimitCents");
        }
        return List.copyOf(missing);
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
