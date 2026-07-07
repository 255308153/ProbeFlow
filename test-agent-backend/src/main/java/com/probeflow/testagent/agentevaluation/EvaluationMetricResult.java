package com.probeflow.testagent.agentevaluation;

import java.util.Map;

public record EvaluationMetricResult(
    String metricName,
    double score,
    double threshold,
    double weight,
    boolean passed,
    Map<String, Object> actual,
    Map<String, Object> expected,
    String diagnosticMessage
) {

    public EvaluationMetricResult {
        metricName = required(metricName, "metricName");
        score = normalize(score);
        threshold = normalize(threshold);
        weight = weight <= 0.0d || Double.isNaN(weight) || Double.isInfinite(weight) ? 1.0d : weight;
        actual = actual == null ? Map.of() : Map.copyOf(actual);
        expected = expected == null ? Map.of() : Map.copyOf(expected);
        diagnosticMessage = diagnosticMessage == null ? "" : diagnosticMessage.trim();
    }

    public static EvaluationMetricResult passed(
        String metricName,
        double threshold,
        double weight,
        String diagnosticMessage,
        Map<String, Object> actual,
        Map<String, Object> expected
    ) {
        return new EvaluationMetricResult(metricName, 1.0d, threshold, weight, true, actual, expected, diagnosticMessage);
    }

    public static EvaluationMetricResult failed(
        String metricName,
        double threshold,
        double weight,
        String diagnosticMessage,
        Map<String, Object> actual,
        Map<String, Object> expected
    ) {
        return new EvaluationMetricResult(metricName, 0.0d, threshold, weight, false, actual, expected, diagnosticMessage);
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
