package com.probeflow.testagent.httpexecution;

import java.util.Map;

public record HttpExecutionCaseResult(
    String caseId,
    String executionRecordId,
    HttpExecutionOutcomeStatus status,
    long durationMs,
    Integer statusCode,
    String message,
    Map<String, Object> requestSnapshot
) {

    public HttpExecutionCaseResult(
        String caseId,
        String executionRecordId,
        HttpExecutionOutcomeStatus status,
        long durationMs,
        Integer statusCode,
        String message
    ) {
        this(caseId, executionRecordId, status, durationMs, statusCode, message, Map.of());
    }
}
