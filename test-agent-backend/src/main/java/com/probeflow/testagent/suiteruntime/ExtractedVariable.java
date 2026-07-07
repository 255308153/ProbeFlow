package com.probeflow.testagent.suiteruntime;

import java.util.Map;

public record ExtractedVariable(
    boolean success,
    boolean blockingFailure,
    String stepId,
    String sourceType,
    String sourcePath,
    String targetScope,
    String targetKey,
    Object value,
    Map<String, Object> diagnostic
) {

    public static ExtractedVariable success(
        String stepId,
        String sourceType,
        String sourcePath,
        String targetScope,
        String targetKey,
        Object value
    ) {
        return new ExtractedVariable(true, false, stepId, sourceType, sourcePath, targetScope, targetKey, value, Map.of());
    }

    public static ExtractedVariable failure(
        boolean blockingFailure,
        String stepId,
        String sourceType,
        String sourcePath,
        String targetScope,
        String targetKey,
        Map<String, Object> diagnostic
    ) {
        return new ExtractedVariable(false, blockingFailure, stepId, sourceType, sourcePath, targetScope, targetKey, null, diagnostic);
    }
}
