package com.probeflow.testagent.knowledge;

import java.util.Map;

public record ManualRealEmbeddingClientRequest(
    String endpoint,
    String apiKey,
    String model,
    String input,
    String usage,
    int timeoutMs,
    int maxInputTokens,
    int batchSize,
    Map<String, Object> metadata
) {

    public ManualRealEmbeddingClientRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
