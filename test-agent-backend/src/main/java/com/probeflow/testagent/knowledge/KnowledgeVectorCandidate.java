package com.probeflow.testagent.knowledge;

public record KnowledgeVectorCandidate(
    KnowledgeChunk chunk,
    double vectorDistance,
    int candidateRank
) {
}
