package com.probeflow.testagent.failureanalysis;

import java.util.List;

public record TaskFailureAnalysisResult(
    String taskId,
    FailureAnalysisMode mode,
    TaskFailureAnalysisCounts counts,
    List<FailureAnalysisResult> executionResults,
    List<GroupedFailureSummary> groupedFailures
) {
}
