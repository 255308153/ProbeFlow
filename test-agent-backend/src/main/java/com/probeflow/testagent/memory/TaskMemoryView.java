package com.probeflow.testagent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TaskMemoryView(
    String memoryId,
    String taskId,
    MemoryScopeType scopeType,
    String summary,
    String content,
    List<String> tags,
    MemorySourceType sourceType,
    String sourceRef,
    Float confidence,
    String lifecycleStage,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt,
    Instant expiresAt
) {
}
