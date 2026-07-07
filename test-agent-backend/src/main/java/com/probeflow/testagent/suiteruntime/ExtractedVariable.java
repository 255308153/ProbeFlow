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
    boolean required,
    String failureStrategy,
    boolean fallbackApplied,
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
        return success(stepId, sourceType, sourcePath, targetScope, targetKey, value, true, "FAIL_FAST", false);
    }

    public static ExtractedVariable success(
        String stepId,
        String sourceType,
        String sourcePath,
        String targetScope,
        String targetKey,
        Object value,
        boolean required,
        String failureStrategy,
        boolean fallbackApplied
    ) {
        return new ExtractedVariable(
            true,
            false,
            stepId,
            sourceType,
            sourcePath,
            targetScope,
            targetKey,
            value,
            required,
            failureStrategy,
            fallbackApplied,
            Map.of()
        );
    }

    public static ExtractedVariable failure(
        boolean blockingFailure,
        String stepId,
        String sourceType,
        String sourcePath,
        String targetScope,
        String targetKey,
        boolean required,
        String failureStrategy,
        Map<String, Object> diagnostic
    ) {
        return new ExtractedVariable(
            false,
            blockingFailure,
            stepId,
            sourceType,
            sourcePath,
            targetScope,
            targetKey,
            null,
            required,
            failureStrategy,
            false,
            diagnostic
        );
    }
}
