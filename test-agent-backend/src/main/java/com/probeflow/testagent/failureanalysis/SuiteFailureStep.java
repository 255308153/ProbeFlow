package com.probeflow.testagent.failureanalysis;

public record SuiteFailureStep(
    String stepId,
    String stepName,
    Integer order,
    String apiSpecId,
    String status,
    Integer statusCode,
    String message,
    String skipReason
) {
}
