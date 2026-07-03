package com.probeflow.testagent.knowledge;

public record KnowledgeIngestResult(
    String documentId,
    String documentRevisionId,
    int version,
    boolean documentCreated,
    boolean revisionCreated,
    boolean idempotent
) {
}
