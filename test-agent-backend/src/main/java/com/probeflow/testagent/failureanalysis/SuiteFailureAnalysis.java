package com.probeflow.testagent.failureanalysis;

import java.util.List;

public record SuiteFailureAnalysis(
    boolean suiteExecution,
    FailureClassification classification,
    SuiteFailureStep firstFailingStep,
    SuiteFailureStep rootCauseStep,
    SuiteFailureStep directlyFailedStep,
    List<SuiteFailureStep> affectedDownstreamSteps,
    List<SuiteFailureStep> dependentSkippedSteps,
    SuiteVariableFailure variableFailure,
    List<SuiteVariableFailure> variableFindings,
    int totalSteps,
    int skippedStepCount,
    int dependentSkippedStepCount,
    String impactSummary
) {

    public SuiteFailureAnalysis {
        affectedDownstreamSteps = affectedDownstreamSteps == null ? List.of() : List.copyOf(affectedDownstreamSteps);
        dependentSkippedSteps = dependentSkippedSteps == null ? List.of() : List.copyOf(dependentSkippedSteps);
        variableFindings = variableFindings == null ? List.of() : List.copyOf(variableFindings);
    }

    public static SuiteFailureAnalysis none() {
        return new SuiteFailureAnalysis(
            false,
            FailureClassification.NONE,
            null,
            null,
            null,
            List.of(),
            List.of(),
            null,
            List.of(),
            0,
            0,
            0,
            null
        );
    }
}
