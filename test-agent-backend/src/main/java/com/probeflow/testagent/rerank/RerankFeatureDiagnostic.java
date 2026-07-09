package com.probeflow.testagent.rerank;

public record RerankFeatureDiagnostic(
    String code,
    String feature,
    String message
) {
    public static RerankFeatureDiagnostic missingFeature(String feature) {
        return new RerankFeatureDiagnostic(
            "missing-feature:" + feature,
            feature,
            "Feature '" + feature + "' was unavailable on the retrieval candidate."
        );
    }
}
