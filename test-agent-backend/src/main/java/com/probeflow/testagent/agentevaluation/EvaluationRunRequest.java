package com.probeflow.testagent.agentevaluation;

public record EvaluationRunRequest(
    String datasetName,
    String runProfile,
    EvaluationProviderMode providerMode
) {

    public EvaluationRunRequest {
        datasetName = datasetName == null || datasetName.isBlank() ? null : datasetName.trim();
        runProfile = runProfile == null || runProfile.isBlank() ? "ci-deterministic" : runProfile.trim();
        providerMode = providerMode == null ? EvaluationProviderMode.DETERMINISTIC_FAKE : providerMode;
    }
}
