package com.probeflow.testagent.agentevaluation;

import java.util.List;

public record EvaluationCaseResult(
    String fixtureId,
    EvaluationCaseStatus status,
    List<String> capabilityTags,
    String actualSummary,
    String expectedSummary,
    List<String> failureReasons,
    List<EvaluationMetricResult> metricResults
) {

    public EvaluationCaseResult {
        fixtureId = required(fixtureId, "fixtureId");
        status = status == null ? EvaluationCaseStatus.PASSED : status;
        capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
        actualSummary = actualSummary == null ? "" : actualSummary.trim();
        expectedSummary = expectedSummary == null ? "" : expectedSummary.trim();
        failureReasons = failureReasons == null ? List.of() : failureReasons.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .toList();
        metricResults = metricResults == null ? List.of() : List.copyOf(metricResults);
    }

    public static EvaluationCaseResult passed(
        String fixtureId,
        List<String> capabilityTags,
        String actualSummary,
        String expectedSummary,
        List<EvaluationMetricResult> metricResults
    ) {
        return new EvaluationCaseResult(
            fixtureId,
            EvaluationCaseStatus.PASSED,
            capabilityTags,
            actualSummary,
            expectedSummary,
            List.of(),
            metricResults
        );
    }

    public static EvaluationCaseResult failed(
        String fixtureId,
        List<String> capabilityTags,
        String actualSummary,
        String expectedSummary,
        List<String> failureReasons,
        List<EvaluationMetricResult> metricResults
    ) {
        return new EvaluationCaseResult(
            fixtureId,
            EvaluationCaseStatus.FAILED,
            capabilityTags,
            actualSummary,
            expectedSummary,
            failureReasons,
            metricResults
        );
    }

    public EvaluationCaseResult withStatus(EvaluationCaseStatus newStatus) {
        return new EvaluationCaseResult(
            fixtureId,
            newStatus,
            capabilityTags,
            actualSummary,
            expectedSummary,
            failureReasons,
            metricResults
        );
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
