package com.probeflow.testagent.memory;

import java.util.List;
import java.util.Map;

public record SessionMemoryWriteRequest(
    String sessionId,
    MemoryScopeType scopeType,
    String summary,
    String content,
    List<String> tags,
    MemorySourceType sourceType,
    String sourceRef,
    Float confidence,
    Map<String, Object> metadata,
    Long ttlSeconds
) {
}
