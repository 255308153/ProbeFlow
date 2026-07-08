package com.probeflow.testagent.memory;

import java.util.Map;

public record MemoryFactEvidence(
    MemorySourceType sourceType,
    String sourceRef,
    String taskId,
    String summary,
    String sanitizedEvidence,
    Map<String, Object> attributes
) {}
