package com.probeflow.testagent.memory;

import java.util.List;

public record LongTermMemoryQuery(
    String stageProfile,
    String rawQuery,
    String systemName,
    String moduleName,
    String apiPath,
    String errorCode,
    List<String> tags,
    List<MemoryScopeType> scopeTypes,
    Integer limit,
    Integer tokenBudget
) {
}
