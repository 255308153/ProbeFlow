package com.probeflow.testagent.llm;

public interface LlmProvider {

    String providerName();

    LlmResponse generate(LlmRequest request);
}
