package com.probeflow.testagent.llm;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LlmPolicy {

    private final boolean allowRealProviders;
    private final Set<String> allowedProviders;

    public LlmPolicy(
        @Value("${probeflow.llm.allow-real-providers:false}") boolean allowRealProviders,
        @Value("${probeflow.llm.allowed-providers:fake}") String allowedProviders
    ) {
        this.allowRealProviders = allowRealProviders;
        this.allowedProviders = parseProviders(allowedProviders);
    }

    public LlmPolicyDecision evaluate(LlmExecutionOptions options) {
        var provider = options == null ? null : options.provider();
        if (provider == null) {
            return LlmPolicyDecision.block("LLM provider is required by policy");
        }
        if (!allowedProviders.isEmpty() && !allowedProviders.contains(provider)) {
            return LlmPolicyDecision.block("LLM provider is not allowed by policy: " + provider);
        }
        if (!FakeLlmProvider.PROVIDER_NAME.equals(provider) && !allowRealProviders) {
            return LlmPolicyDecision.block("Real LLM providers are disabled by policy: " + provider);
        }
        return LlmPolicyDecision.allow();
    }

    private Set<String> parseProviders(String providers) {
        if (providers == null || providers.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(providers.split(","))
            .map(String::trim)
            .filter(provider -> !provider.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }
}
