package com.probeflow.testagent.demorun;

import java.util.LinkedHashMap;
import java.util.Map;

public record DemoRunArtifactReference(
    String artifactType,
    String path,
    String mediaType,
    Map<String, Object> metadata
) {

    public DemoRunArtifactReference {
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
