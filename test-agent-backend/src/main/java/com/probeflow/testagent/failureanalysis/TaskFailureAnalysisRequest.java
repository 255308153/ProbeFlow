package com.probeflow.testagent.failureanalysis;

import java.util.List;

public record TaskFailureAnalysisRequest(
    String taskId,
    List<String> executionIds,
    FailureAnalysisMode mode
) {
    public static TaskFailureAnalysisRequest basic(String taskId) {
        return new TaskFailureAnalysisRequest(taskId, List.of(), FailureAnalysisMode.BASIC);
    }

    public static TaskFailureAnalysisRequest basic(String taskId, List<String> executionIds) {
        return new TaskFailureAnalysisRequest(taskId, executionIds, FailureAnalysisMode.BASIC);
    }
}
