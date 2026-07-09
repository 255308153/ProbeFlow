package com.probeflow.testagent.memory;

import com.probeflow.testagent.apispec.ApiSpec;
import java.util.List;

public record UnifiedContextQuery(
    String taskId,
    String sessionId,
    String apiSpecId,
    ApiSpec apiSpec,
    String stageProfile,
    String rawQuery,
    String systemName,
    String moduleName,
    String apiPath,
    String errorCode,
    List<String> tags,
    Integer tokenBudget,
    MemoryUsageConsumer consumer,
    String usageSourceRef,
    PostRerankExpandedContext postRerankContext
) {
    public UnifiedContextQuery(
        String taskId,
        String sessionId,
        String apiSpecId,
        ApiSpec apiSpec,
        String stageProfile,
        String rawQuery,
        String systemName,
        String moduleName,
        String apiPath,
        String errorCode,
        List<String> tags,
        Integer tokenBudget,
        MemoryUsageConsumer consumer,
        String usageSourceRef
    ) {
        this(
            taskId,
            sessionId,
            apiSpecId,
            apiSpec,
            stageProfile,
            rawQuery,
            systemName,
            moduleName,
            apiPath,
            errorCode,
            tags,
            tokenBudget,
            consumer,
            usageSourceRef,
            null
        );
    }

    public UnifiedContextQuery(
        String taskId,
        String sessionId,
        String apiSpecId,
        ApiSpec apiSpec,
        String stageProfile,
        String rawQuery,
        String systemName,
        String moduleName,
        String apiPath,
        String errorCode,
        List<String> tags,
        Integer tokenBudget
    ) {
        this(
            taskId,
            sessionId,
            apiSpecId,
            apiSpec,
            stageProfile,
            rawQuery,
            systemName,
            moduleName,
            apiPath,
            errorCode,
            tags,
            tokenBudget,
            null,
            null,
            null
        );
    }
}
