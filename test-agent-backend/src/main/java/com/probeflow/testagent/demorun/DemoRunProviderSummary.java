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
    boolean comparisonEnabled,
    boolean allowMemoryWrite,
    List<String> outputFormats,
    List<String> externalDependencyPolicy
) {

    public DemoRunProviderSummary {
        outputFormats = outputFormats == null ? List.of() : List.copyOf(outputFormats);
        externalDependencyPolicy = externalDependencyPolicy == null
            ? List.of()
            : List.copyOf(externalDependencyPolicy);
    }
}
