package com.probeflow.testagent.report;

public record ReportGenerationResult(
    String reportId,
    String taskId,
    String state,
    int caseCount,
    int executionCount
) {
}
