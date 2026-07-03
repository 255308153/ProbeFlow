package com.probeflow.testagent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record LongTermMemoryRetrievalHit(
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
    Integer hitCount,
    Instant lastUsedAt,
    Map<String, Object> metadata,
    int tokenCount,
    double score,
    Map<String, Double> componentScores,
    List<String> matchReasons,
    boolean lowConfidence
) {
}
