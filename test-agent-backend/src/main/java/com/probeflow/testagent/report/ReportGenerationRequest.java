package com.probeflow.testagent.report;

public record ReportGenerationRequest(String taskId) {

    public static ReportGenerationRequest forTask(String taskId) {
        return new ReportGenerationRequest(taskId);
    }
}
