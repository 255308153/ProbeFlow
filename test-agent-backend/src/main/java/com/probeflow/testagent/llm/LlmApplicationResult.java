package com.probeflow.testagent.llm;

public record LlmApplicationResult(
    String llmCallId,
    LlmCallResult callResult,
    String requestHash,
    String templateId,
    String templateVersion
) {

    public boolean succeeded() {
        return callResult != null && callResult.succeeded();
    }
}
