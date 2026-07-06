package com.probeflow.testagent.memory;

import java.util.Map;

public record MemoryUsefulnessFeedbackRequest(
    String usageId,
    String actor,
    MemoryUsefulnessOutcome outcome,
    String reason,
    Map<String, Object> metadata
) {
}
