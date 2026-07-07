package com.probeflow.testagent.agentevaluation;

import java.time.Instant;

public record EvaluationRun(
    String runId,
    String datasetName,
    String datasetVersion,
    String profile,
    EvaluationProviderMode providerMode,
    EvaluationRunStatus status,
    double overallScore,
    Instant startedAt,
    Instant completedAt,
    String summary
) {

    public EvaluationRun {
        runId = required(runId, "runId");
        datasetName = required(datasetName, "datasetName");
        datasetVersion = required(datasetVersion, "datasetVersion");
        profile = profile == null || profile.isBlank() ? "ci-deterministic" : profile.trim();
        providerMode = providerMode == null ? EvaluationProviderMode.DETERMINISTIC_FAKE : providerMode;
        status = status == null ? EvaluationRunStatus.PARTIAL : status;
        overallScore = normalize(overallScore);
        startedAt = startedAt == null ? Instant.now() : startedAt;
        completedAt = completedAt == null ? startedAt : completedAt;
        summary = summary == null ? "" : summary.trim();
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static double normalize(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        if (value < 0.0d) {
            return 0.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }
}
