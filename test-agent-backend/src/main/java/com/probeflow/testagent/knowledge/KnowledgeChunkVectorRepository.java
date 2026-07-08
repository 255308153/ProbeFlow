package com.probeflow.testagent.knowledge;

import java.util.List;

public interface KnowledgeChunkVectorRepository {

    List<KnowledgeVectorCandidate> findPgvectorCandidates(
        String systemName,
        String moduleName,
        String bizEntity,
        DocumentType documentType,
        String apiPath,
        String httpMethod,
        String applicableStage,
        List<String> tags,
        float[] queryEmbedding,
        int candidateLimit
    );
}
