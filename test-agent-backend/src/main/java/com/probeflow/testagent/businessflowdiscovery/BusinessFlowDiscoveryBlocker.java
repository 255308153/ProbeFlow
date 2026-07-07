package com.probeflow.testagent.businessflowdiscovery;

import java.util.LinkedHashMap;
import java.util.Map;

public record BusinessFlowDiscoveryBlocker(
    String code,
    String severity,
    String message,
    Map<String, Object> metadata
) {

    public BusinessFlowDiscoveryBlocker {
        severity = severity == null || severity.isBlank() ? "WARN" : severity;
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
