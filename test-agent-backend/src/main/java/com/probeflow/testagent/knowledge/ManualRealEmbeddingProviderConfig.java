package com.probeflow.testagent.knowledge;

import java.util.ArrayList;
import java.util.List;

public record ManualRealEmbeddingProviderConfig(
    String profileId,
    String endpoint,
    String apiKey,
    String model,
    int dimension,
    int timeoutMs,
    int maxInputTokens,
    String queryPrefix,
    String documentPrefix,
    int batchSize,
    String failurePolicy
) {

    public static final String DEFAULT_PROFILE_ID = "manual-real-embedding";
    public static final String DEFAULT_FAILURE_POLICY = "fail-fast";

    public ManualRealEmbeddingProviderConfig {
        profileId = clean(profileId, DEFAULT_PROFILE_ID);
        endpoint = clean(endpoint, null);
        apiKey = clean(apiKey, null);
        model = clean(model, null);
        queryPrefix = queryPrefix == null ? "" : queryPrefix;
        documentPrefix = documentPrefix == null ? "" : documentPrefix;
        failurePolicy = clean(failurePolicy, null);
    }

    public EmbeddingProfile profile() {
        return new EmbeddingProfile(
            EmbeddingProviderMode.REAL,
            profileId,
            model,
            dimension,
            "manual-profile",
            queryPrefix,
            documentPrefix
        );
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
        if (dimension < 1) {
            missing.add("dimension");
        }
        if (timeoutMs < 1) {
            missing.add("timeoutMs");
        }
        if (maxInputTokens < 1) {
            missing.add("maxInputTokens");
        }
        if (batchSize < 1) {
            missing.add("batchSize");
        }
        if (failurePolicy == null) {
            missing.add("failurePolicy");
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
