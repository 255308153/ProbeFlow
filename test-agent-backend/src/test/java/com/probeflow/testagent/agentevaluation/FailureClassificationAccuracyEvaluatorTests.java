package com.probeflow.testagent.agentevaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FailureClassificationAccuracyEvaluatorTests {

    @Autowired
    private FailureClassificationAccuracyEvaluator evaluator;

    @Test
    void applicationServiceRunsFailureClassificationDatasetThroughFailureAnalysis() {
        var service = new AgentEvaluationApplicationService(
            new EvaluationDatasetRegistry(),
            List.of(evaluator)
        );

        var result = service.run(new EvaluationRunRequest(
            EvaluationDatasetRegistry.FAILURE_CLASSIFICATION_DATASET,
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE
        ));

        assertThat(result.run().status())
            .as(result.report().humanReadableSummary() + " fixes=" + result.report().recommendedFixes())
            .isEqualTo(EvaluationRunStatus.PASSED);
        assertThat(result.report().metricSummary()).containsKey(FailureClassificationAccuracyEvaluator.METRIC_NAME);
        assertThat(result.report().caseSummary())
            .extracting(summary -> summary.get("fixtureId"))
            .containsExactly(
                "failure-auth-401",
                "failure-validation-422",
                "failure-environment-missing",
                "failure-server-503",
                "failure-high-value-memory-candidate"
            );
    }

    @Test
    void reportsReasonRiskSuggestionAndMissingCandidateDiagnostics() {
        var result = evaluator.evaluate(
            dataset(),
            fixture(
                "failure-diagnostic-status-mismatch",
                Map.of(
                    "overallStatus", "FAILED",
                    "statusCode", 409,
                    "criticalFailed", false,
                    "assertionResults", List.of(Map.of(
                        "name", "expected status",
                        "type", "STATUS_CODE",
                        "expected", 201,
                        "actual", 409,
                        "status", "FAILED",
                        "critical", true
                    ))
                ),
                Map.of(
                    "classification", "AUTH_ISSUE",
                    "riskLevel", "HIGH",
                    "nextSuggestionContains", "Inspect auth variables",
                    "memoryCandidatePresent", true
                )
            ),
            context("eval-failure-diagnostics")
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.FAILED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> {
                assertThat(metric.passed()).isFalse();
                assertThat(metric.actual()).containsEntry("classification", "STATUS_MISMATCH");
                assertThat(metric.diagnosticMessage())
                    .contains("reason mismatch")
                    .contains("risk mismatch")
                    .contains("suggestion mismatch")
                    .contains("missing candidate");
            });
    }

    @Test
    void evaluatesHighValueReusableMemoryCandidatePresence() {
        var result = evaluator.evaluate(
            dataset(),
            fixture(
                "failure-high-value-candidate-direct",
                Map.of(
                    "overallStatus", "FAILED",
                    "statusCode", 503,
                    "requestPath", "/api/payments/capture",
                    "criticalFailed", true
                ),
                Map.of(
                    "classification", "SERVER_ERROR",
                    "riskLevel", "HIGH",
                    "nextSuggestionContains", "Retry once",
                    "memoryCandidatePresent", true
                )
            ),
            context("eval-failure-memory")
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.PASSED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> assertThat(metric.actual())
                .containsEntry("memoryCandidatePresent", true)
                .containsEntry("memoryCandidateCreated", true));
    }

    @Test
    void classifiesEnvironmentFailureFromBlockedExecutionFacts() {
        var result = evaluator.evaluate(
            dataset(),
            fixture(
                "failure-environment-direct",
                Map.of(
                    "overallStatus", "BLOCKED",
                    "responseSnapshot", Map.of("errorType", "INVALID_REQUEST"),
                    "errorMessage", "Unsupported protocol ftp://target",
                    "criticalFailed", false
                ),
                Map.of(
                    "classification", "ENVIRONMENT_ISSUE",
                    "riskLevel", "MEDIUM",
                    "nextSuggestionContains", "Inspect environment variables",
                    "memoryCandidatePresent", true
                )
            ),
            context("eval-failure-environment")
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.PASSED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> assertThat(metric.actual()).containsEntry("classification", "ENVIRONMENT_ISSUE"));
    }

    private GoldenTaskFixture fixture(
        String fixtureId,
        Map<String, Object> setup,
        Map<String, Object> expected
    ) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of("failure-classification"),
            "Evaluate failure classification fixture.",
            EvaluationFixtureType.FAILURE_CLASSIFICATION,
            expected,
            setup
        );
    }

    private EvaluationDataset dataset() {
        return new EvaluationDataset(
            "failure-classification-test",
            "2026-07-07",
            List.of(),
            0.8d,
            Map.of(FailureClassificationAccuracyEvaluator.METRIC_NAME, 0.8d),
            Map.of(FailureClassificationAccuracyEvaluator.METRIC_NAME, 2.0d),
            Map.of()
        );
    }

    private EvaluationRunContext context(String runId) {
        return new EvaluationRunContext(
            runId,
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE,
            "fixture-" + runId
        );
    }
}
