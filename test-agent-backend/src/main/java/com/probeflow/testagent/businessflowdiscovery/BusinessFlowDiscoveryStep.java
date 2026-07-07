package com.probeflow.testagent.businessflowdiscovery;

import com.probeflow.testagent.apispec.HttpMethod;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BusinessFlowDiscoveryStep(
    String stepId,
    int order,
    String apiSpecId,
    String stepName,
    BusinessFlowOperationKind operationKind,
    HttpMethod httpMethod,
    String path,
    boolean critical,
    List<String> sourceRefs,
    Map<String, Object> metadata
) {

    public BusinessFlowDiscoveryStep {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
