package com.probeflow.testagent.knowledge;

import java.util.List;
import java.util.Map;

public record KnowledgeContextEntry(
    String chunkId,
    String documentId,
    String documentRevisionId,
    String title,
    double score,
    String evidenceType,
    String sourceRef,
    Map<String, Object> metadata,
    List<String> matchReasons,
    boolean lowConfidence
) {
}
