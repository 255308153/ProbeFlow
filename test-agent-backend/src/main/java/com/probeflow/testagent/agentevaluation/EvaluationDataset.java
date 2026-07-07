package com.probeflow.testagent.agentevaluation;

import java.util.List;
import java.util.Map;

public record EvaluationDataset(
    String name,
    String version,
    List<GoldenTaskFixture> fixtures,
    double defaultThreshold,
    Map<String, Double> metricThresholds,
    Map<String, Double> metricWeights,
    Map<String, Double> caseThresholds
) {

    public EvaluationDataset {
        name = required(name, "dataset name");
        version = required(version, "dataset version");
        fixtures = fixtures == null ? List.of() : List.copyOf(fixtures);
        defaultThreshold = normalize(defaultThreshold, 0.8d);
        metricThresholds = metricThresholds == null ? Map.of() : Map.copyOf(metricThresholds);
        metricWeights = metricWeights == null ? Map.of() : Map.copyOf(metricWeights);
        caseThresholds = caseThresholds == null ? Map.of() : Map.copyOf(caseThresholds);
    }

    public double thresholdFor(String metricName) {
        return metricThresholds.getOrDefault(metricName, defaultThreshold);
    }

    public double weightFor(String metricName) {
        return metricWeights.getOrDefault(metricName, 1.0d);
    }

    public double caseThresholdFor(String fixtureId) {
        return caseThresholds.getOrDefault(fixtureId, defaultThreshold);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static double normalize(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0d || value > 1.0d) {
            return fallback;
        }
        return value;
    }
}
