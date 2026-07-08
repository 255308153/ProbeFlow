package com.probeflow.testagent.failureanalysis;

public record SuiteVariableFailure(
    FailureClassification classification,
    String stepId,
    String expression,
    String location,
    String scope,
    String path,
    String extractRuleId,
    String sourceType,
    String sourcePath,
    String targetScope,
    String targetKey,
    String failureReason,
    String oldValueSummary,
    String newValueSummary
) {
}
