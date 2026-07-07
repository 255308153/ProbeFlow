package com.probeflow.testagent.businessflowdiscovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BusinessFlowCandidate(
    String candidateId,
    String scenarioName,
    boolean primary,
    double confidence,
    boolean requiresHumanReview,
    List<BusinessFlowDiscoveryStep> steps,
    List<BusinessFlowDiscoveryEvidence> evidence,
    List<BusinessFlowDiscoveryBlocker> blockers,
    BusinessFlowSourceCoverage sourceCoverage,
    List<String> tags,
    Map<String, Object> metadata
) {

    public BusinessFlowCandidate {
        steps = steps == null ? List.of() : List.copyOf(steps);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        tags = tags == null ? List.of() : List.copyOf(tags);
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
