package com.probeflow.testagent.llm;

public record ManualRealLlmClientResponse(
    int statusCode,
    String body,
    String providerTraceId
) {

    public ManualRealLlmClientResponse {
        body = body == null ? "" : body;
        providerTraceId = providerTraceId == null || providerTraceId.isBlank() ? null : providerTraceId.trim();
    }
}
