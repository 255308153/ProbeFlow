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
    List<String> rootCauseStepIds,
    List<String> affectedDownstreamStepIds,
    List<String> impactSummaries,
    List<String> priorityActions,
    int occurrenceCount,
    String summary
) {

    public GroupedFailureSummary {
        affectedCaseIds = affectedCaseIds == null ? List.of() : List.copyOf(affectedCaseIds);
        executionIds = executionIds == null ? List.of() : List.copyOf(executionIds);
        rootCauseStepIds = rootCauseStepIds == null ? List.of() : List.copyOf(rootCauseStepIds);
        affectedDownstreamStepIds = affectedDownstreamStepIds == null ? List.of() : List.copyOf(affectedDownstreamStepIds);
        impactSummaries = impactSummaries == null ? List.of() : List.copyOf(impactSummaries);
        priorityActions = priorityActions == null ? List.of() : List.copyOf(priorityActions);
    }
}
