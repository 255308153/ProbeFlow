package com.probeflow.testagent.memory;

import com.probeflow.testagent.retrieval.RetrievalRouteEvidence;
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
    boolean lowConfidence,
    List<RetrievalRouteEvidence> routeEvidence
) {

    public LongTermMemoryRetrievalHit {
        routeEvidence = routeEvidence == null ? List.of() : List.copyOf(routeEvidence);
    }

    public LongTermMemoryRetrievalHit(
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
        this(
            memoryId,
            scopeType,
            summary,
            content,
            fullContent,
            tags,
            sourceType,
            sourceRef,
            confidence,
            importance,
            successContribution,
            hitCount,
            lastUsedAt,
            metadata,
            tokenCount,
            score,
            componentScores,
            matchReasons,
            lowConfidence,
            List.of()
        );
    }
}
