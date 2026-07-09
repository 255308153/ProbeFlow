package com.probeflow.testagent.knowledge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record KnowledgeRetrievalHit(
    String chunkId,
    String documentId,
    String documentRevisionId,
    String chunkTitle,
    String chunkContent,
    String sourceRef,
    DocumentType documentType,
    DocumentAuthority authority,
    String systemName,
    String moduleName,
    String bizEntity,
    List<String> tags,
    List<String> applicableStages,
    Map<String, Object> metadata,
    int tokenCount,
    double score,
    Map<String, Double> componentScores,
    List<String> matchReasons,
    boolean lowConfidence,
    List<KnowledgeRouteEvidence> routeEvidence
) {

    public KnowledgeRetrievalHit {
        tags = tags == null ? List.of() : List.copyOf(tags);
        applicableStages = applicableStages == null ? List.of() : List.copyOf(applicableStages);
        metadata = metadata == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        componentScores = componentScores == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(componentScores));
        matchReasons = matchReasons == null ? List.of() : List.copyOf(matchReasons);
        routeEvidence = routeEvidence == null ? List.of() : List.copyOf(routeEvidence);
    }

    public KnowledgeRetrievalHit(
        String chunkId,
        String documentId,
        String documentRevisionId,
        String chunkTitle,
        String chunkContent,
        String sourceRef,
        DocumentType documentType,
        DocumentAuthority authority,
        String systemName,
        String moduleName,
        String bizEntity,
        List<String> tags,
        List<String> applicableStages,
        Map<String, Object> metadata,
        int tokenCount,
        double score,
        Map<String, Double> componentScores,
        List<String> matchReasons,
        boolean lowConfidence
    ) {
        this(
            chunkId,
            documentId,
            documentRevisionId,
            chunkTitle,
            chunkContent,
            sourceRef,
            documentType,
            authority,
            systemName,
            moduleName,
            bizEntity,
            tags,
            applicableStages,
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
