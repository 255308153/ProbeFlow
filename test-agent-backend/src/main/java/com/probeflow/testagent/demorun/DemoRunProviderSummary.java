package com.probeflow.testagent.demorun;

import java.util.List;

public record DemoRunProviderSummary(
    DemoRunProviderMode providerMode,
    String harnessProviderMode,
    String requestedProviderMode,
    String runProfile,
    boolean usesRealLlm,
    boolean usesExternalHttp,
    boolean fakeBaseline,
    List<String> externalDependencyPolicy
) {

    public DemoRunProviderSummary {
        externalDependencyPolicy = externalDependencyPolicy == null
            ? List.of()
            : List.copyOf(externalDependencyPolicy);
    }
}
