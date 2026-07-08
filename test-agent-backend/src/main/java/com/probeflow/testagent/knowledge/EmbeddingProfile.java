package com.probeflow.testagent.knowledge;

public record EmbeddingProfile(
    EmbeddingProviderMode providerMode,
    String profileId,
    String model,
    int dimension,
    String configSource,
    String queryPrefix,
    String documentPrefix
) {

    public EmbeddingProfile {
        if (providerMode == null) {
            throw new IllegalArgumentException("embedding provider mode must not be null");
        }
        profileId = requireNonBlank(profileId, "embedding profile id must not be blank");
        model = requireNonBlank(model, "embedding model must not be blank");
        configSource = requireNonBlank(configSource, "embedding config source must not be blank");
        if (dimension <= 0) {
            throw new IllegalArgumentException("embedding dimension must be positive");
        }
        queryPrefix = queryPrefix == null ? "" : queryPrefix;
        documentPrefix = documentPrefix == null ? "" : documentPrefix;
    }

    public static EmbeddingProfile fake(int dimension) {
        return new EmbeddingProfile(
            EmbeddingProviderMode.FAKE,
            "fake-default",
            "deterministic-sha256-v1",
            dimension,
            "application-default",
            "Represent this sentence for searching relevant passages: ",
            ""
        );
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
