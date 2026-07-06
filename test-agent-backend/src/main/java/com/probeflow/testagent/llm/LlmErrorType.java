package com.probeflow.testagent.llm;

public enum LlmErrorType {
    NONE,
    TIMEOUT,
    RATE_LIMITED,
    PROVIDER_ERROR,
    NETWORK_ERROR,
    TEMPLATE_RENDER_ERROR,
    POLICY_BLOCKED,
    OUTPUT_PARSE_ERROR
}
