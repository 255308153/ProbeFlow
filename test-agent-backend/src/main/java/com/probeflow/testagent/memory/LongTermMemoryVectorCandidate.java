package com.probeflow.testagent.memory;

public record LongTermMemoryVectorCandidate(
    LongTermMemory memory,
    double vectorDistance,
    int candidateRank
) {
}
