package com.probeflow.testagent.manualsuiteagent;

import java.util.LinkedHashMap;
import java.util.Map;

public record ManualSuiteAgentArtifactReference(
    String artifactType,
    String path,
    String mediaType,
    Map<String, Object> metadata
) {

    public ManualSuiteAgentArtifactReference {
        metadata = metadata == null
            ? Map.of()
            : new LinkedHashMap<>(new ManualSuiteAgentRedactor().redactMap(metadata));
    }
}
