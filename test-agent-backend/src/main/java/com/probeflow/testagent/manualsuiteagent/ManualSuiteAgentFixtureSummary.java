package com.probeflow.testagent.manualsuiteagent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ManualSuiteAgentFixtureSummary(
    String fixtureId,
    String fixtureVersion,
    String displayName,
    String description,
    List<String> capabilityTags,
    Map<String, Object> metadata
) {

    public ManualSuiteAgentFixtureSummary {
        capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
