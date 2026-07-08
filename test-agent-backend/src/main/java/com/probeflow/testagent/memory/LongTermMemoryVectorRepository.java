package com.probeflow.testagent.memory;

import java.util.List;

public interface LongTermMemoryVectorRepository {

    List<LongTermMemoryVectorCandidate> findPgvectorCandidates(
        List<MemoryScopeType> scopeTypes,
        String systemName,
        String moduleName,
        String apiPath,
        String errorCode,
        List<String> tags,
        String stageProfile,
        float[] queryEmbedding,
        int candidateLimit
    );
}
