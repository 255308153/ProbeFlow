package com.probeflow.testagent.failureanalysis;

import com.probeflow.testagent.executionrecord.OverallStatus;
import java.util.List;

public record FailureAnalysisResult(
    String executionId,
    String taskId,
    String caseId,
    String stepId,
    FailureAnalysisMode mode,
    OverallStatus overallStatus,
    Integer statusCode,
    Long durationMs,
    String environment,
    RequestFacts request,
    ResponseFacts response,
    List<FailedAssertionSummary> failedAssertions,
    String errorMessage,
    FailureClassification classification,
    List<String> evidence,
    List<String> observationIds,
    String riskLevel,
    String summary,
    String failureReason,
    String nextSuggestion,
    boolean retryable,
    String retryReason,
    SuiteFailureSummary suiteFailure
) {
}
