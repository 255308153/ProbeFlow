package com.probeflow.testagent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SessionMemoryView(
    String memoryId,
    String sessionId,
    MemoryScopeType scopeType,
    String summary,
    String content,
    List<String> tags,
    MemorySourceType sourceType,
    String sourceRef,
    Float confidence,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant expiresAt,
    Long ttlSeconds
) {
}
