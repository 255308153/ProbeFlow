package com.probeflow.testagent.knowledge;

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
    boolean lowConfidence
) {
}
