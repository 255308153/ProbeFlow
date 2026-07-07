package com.probeflow.testagent.suitedraft;

public record SuiteDraftGenerationOptions(
    SuiteVariableScope defaultTargetScope,
    double autoReadyConfidenceThreshold,
    double blockedConfidenceThreshold,
    boolean preferStepScopedReferences,
    String generationMode
) {

    public SuiteDraftGenerationOptions {
        defaultTargetScope = defaultTargetScope == null ? SuiteVariableScope.SUITE : defaultTargetScope;
        autoReadyConfidenceThreshold = autoReadyConfidenceThreshold <= 0 ? 0.75 : autoReadyConfidenceThreshold;
        blockedConfidenceThreshold = blockedConfidenceThreshold <= 0 ? 0.40 : blockedConfidenceThreshold;
        generationMode = generationMode == null || generationMode.isBlank() ? "DETERMINISTIC_GENERATION" : generationMode;
    }

    public static SuiteDraftGenerationOptions defaults() {
        return new SuiteDraftGenerationOptions(
            SuiteVariableScope.SUITE,
            0.75,
            0.40,
            false,
            "DETERMINISTIC_GENERATION"
        );
    }
}
