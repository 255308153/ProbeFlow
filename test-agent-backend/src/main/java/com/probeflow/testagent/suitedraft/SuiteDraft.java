package com.probeflow.testagent.suitedraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SuiteDraft(
    String flowId,
    String scenarioName,
    List<SuiteDraftStep> steps,
    Map<String, Object> metadata
) {

    public SuiteDraft {
        steps = steps == null ? List.of() : List.copyOf(steps);
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
