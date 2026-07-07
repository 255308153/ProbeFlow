package com.probeflow.testagent.agentevaluation;

public record EvaluationRunContext(
    String runId,
    String runProfile,
    EvaluationProviderMode providerMode,
    String fixtureNamespace
) {

    public EvaluationRunContext {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        runId = runId.trim();
        runProfile = runProfile == null || runProfile.isBlank() ? "ci-deterministic" : runProfile.trim();
        providerMode = providerMode == null ? EvaluationProviderMode.DETERMINISTIC_FAKE : providerMode;
        fixtureNamespace = fixtureNamespace == null || fixtureNamespace.isBlank()
            ? "fixture-" + runId
            : fixtureNamespace.trim();
    }

    public boolean deterministicFake() {
        return providerMode == EvaluationProviderMode.DETERMINISTIC_FAKE;
    }
}
