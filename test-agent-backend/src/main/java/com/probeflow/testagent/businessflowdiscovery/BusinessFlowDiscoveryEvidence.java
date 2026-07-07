package com.probeflow.testagent.businessflowdiscovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BusinessFlowDiscoveryEvidence(
    String evidenceId,
    BusinessFlowEvidenceSource source,
    String summary,
    double confidenceContribution,
    List<String> refs,
    Map<String, Object> metadata
) {

    public BusinessFlowDiscoveryEvidence {
        refs = refs == null ? List.of() : List.copyOf(refs);
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
