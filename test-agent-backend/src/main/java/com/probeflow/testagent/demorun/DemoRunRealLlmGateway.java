package com.probeflow.testagent.demorun;

import java.util.Map;

public interface DemoRunRealLlmGateway {

    DemoRunRealLlmProbeResult probe(DemoRunRequest request, Map<String, Object> inputSummary);

    static DemoRunRealLlmGateway disabled() {
        return (request, inputSummary) -> DemoRunRealLlmProbeResult.blocked(
            "Manual real LLM mode is not configured. Required configuration includes endpoint, key, model, timeoutMs, maxTokens and costLimitCents.",
            Map.of(
                "missingFields",
                java.util.List.of("endpoint", "key", "model", "timeoutMs", "maxTokens", "costLimitCents"),
                "requestedProviderMode", request.providerMode().name(),
                "runProfile", request.runProfile()
            )
        );
    }
}
