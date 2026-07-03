package com.probeflow.testagent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RefinedMemoryView(
    String memoryId,
    MemoryScopeType scopeType,
    String summary,
    String content,
    String fullContent,
    List<String> tags,
    MemorySourceType sourceType,
    String sourceRef,
    Float confidence,
    Float importance,
    Float successContribution,
    Map<String, Object> metadata,
    float[] embedding,
    Instant createdAt,
    Instant updatedAt
) {
}
