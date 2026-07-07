package com.probeflow.testagent.businessflowdiscovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BusinessFlowDiscoveryResult(
    String schemaVersion,
    BusinessFlowDiscoveryStatus status,
    String fixtureId,
    BusinessFlowDiscoveryProviderMode providerMode,
    boolean usesManualLlmProvider,
    boolean usesExternalHttp,
    List<BusinessFlowCandidate> candidates,
    List<BusinessFlowDiscoveryBlocker> blockers,
    BusinessFlowSourceCoverage sourceCoverage,
    Map<String, Object> metadata
) {

    public static final String SCHEMA_VERSION = "v3-business-flow-discovery.v1";

    public BusinessFlowDiscoveryResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
