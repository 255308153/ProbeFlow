package com.probeflow.testagent.failureanalysis;

public record FailureAnalysisRequest(
    String executionId,
    FailureAnalysisMode mode
) {

    public static FailureAnalysisRequest basic(String executionId) {
        return new FailureAnalysisRequest(executionId, FailureAnalysisMode.BASIC);
    }
}
