package com.probeflow.testagent.knowledge;

public record ManualRealEmbeddingClientResponse(
    int statusCode,
    String body,
    String providerTraceId
) {
}
