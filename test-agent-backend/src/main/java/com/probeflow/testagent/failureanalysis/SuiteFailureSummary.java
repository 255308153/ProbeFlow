package com.probeflow.testagent.failureanalysis;

import java.util.List;

public record SuiteFailureSummary(
    boolean suiteExecution,
    String failedStepId,
    Integer failedStepOrder,
    String failedStepApiSpecId,
    String failedStepStatus,
    int totalSteps,
    int skippedStepCount,
    int dependentSkippedStepCount,
    List<String> dependentSkippedStepIds,
    String impactSummary
) {
    public static SuiteFailureSummary none() {
        return new SuiteFailureSummary(false, null, null, null, null, 0, 0, 0, List.of(), null);
    }
}
