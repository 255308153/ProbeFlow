package com.probeflow.testagent.memorygraph;

import java.util.List;

public record MemoryGraphQueryResult(
    MemoryGraphSeed seed,
    List<MemoryGraphRelatedEntity> relatedEntities,
    List<MemoryGraphRelatedMemory> relatedMemories
) {
}
