package com.probeflow.testagent.httpexecution;

public record HttpExecutionCaseResult(
    String caseId,
    String executionRecordId,
    HttpExecutionOutcomeStatus status,
    long durationMs,
    Integer statusCode,
    String message
) {
}
