package com.probeflow.testagent.failureanalysis;

import java.util.List;

public record GroupedFailureSummary(
    FailureClassification classification,
    String riskLevel,
    boolean retryable,
    Integer statusCode,
    String apiReference,
    String caseReference,
    String failedAssertionType,
    String errorType,
    List<String> affectedCaseIds,
    List<String> executionIds,
    int occurrenceCount,
    String summary
) {
}
