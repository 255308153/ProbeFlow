package com.probeflow.testagent.memory;

import java.util.List;
import java.util.Map;

public record MemoryCandidateRequest(
    String summary,
    String content,
    MemorySourceType sourceType,
    String sourceRef,
    String taskId,
    List<String> tags,
    Float confidence,
    String rawEvidence,
    Map<String, Object> metadata
) {
}
