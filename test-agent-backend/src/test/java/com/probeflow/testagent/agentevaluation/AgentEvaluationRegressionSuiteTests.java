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
class AgentEvaluationRegressionSuiteTests {

    static final String MAVEN_ENTRYPOINT = "mvn -q -Dtest=AgentEvaluationRegressionSuiteTests test";

    @Autowired
    private AgentEvaluationApplicationService applicationService;

    @Autowired
    private EvaluationDatasetRegistry datasets;

    @Test
    void regressionSuiteRunsAllCoreMetricsWithDeterministicProviderAndAuditableReport() {
        var dataset = datasets.load(EvaluationDatasetRegistry.REGRESSION_SUITE_DATASET);

        assertThat(dataset.metricThresholds()).containsOnlyKeys(
            PlannerDecisionAccuracyEvaluator.METRIC_NAME,
            ToolSelectionPolicyValidityEvaluator.METRIC_NAME,
            ContextCitationUsefulnessEvaluator.METRIC_NAME,
            FailureClassificationAccuracyEvaluator.METRIC_NAME,
            TestCaseCoverageEvaluator.METRIC_NAME,
            ReportUsefulnessNoSecretEvaluator.METRIC_NAME,
            MemoryReuseClosedLoopEvaluator.METRIC_NAME
        );
        assertThat(dataset.metricWeights()).containsEntry(MemoryReuseClosedLoopEvaluator.METRIC_NAME, 2.0d);
        assertThat(dataset.caseThresholds()).containsEntry("regression-memory-reuse-payment-auth-loop", 0.8d);

        var first = runRegressionSuite();
        var second = runRegressionSuite();

        assertPassedRegressionRun(first);
        assertPassedRegressionRun(second);
        assertThat(first.run().runId()).isNotEqualTo(second.run().runId());
        assertThat(MAVEN_ENTRYPOINT).contains("AgentEvaluationRegressionSuiteTests");
    }

    @Test
    void appliesCaseThresholdMetricThresholdWeightingAndFailedMetricReporting() {
        var dataset = new EvaluationDataset(
            "threshold-weight-test",
            "2026-07-07",
            List.of(
                stubFixture("case-threshold-fixture", PlannerDecisionAccuracyEvaluator.METRIC_NAME, 0.75d, 0.70d, true),
                stubFixture("metric-threshold-fixture", MemoryReuseClosedLoopEvaluator.METRIC_NAME, 0.50d, 0.80d, false)
            ),
            0.7d,
            Map.of(
                PlannerDecisionAccuracyEvaluator.METRIC_NAME, 0.70d,
                MemoryReuseClosedLoopEvaluator.METRIC_NAME, 0.80d
            ),
            Map.of(
                PlannerDecisionAccuracyEvaluator.METRIC_NAME, 1.0d,
                MemoryReuseClosedLoopEvaluator.METRIC_NAME, 3.0d
            ),
            Map.of(
                "case-threshold-fixture", 0.80d,
                "metric-threshold-fixture", 0.70d
            )
        );
        var service = new AgentEvaluationApplicationService(
            new StaticDatasetRegistry(dataset),
            List.of(new StubEvaluator())
        );

        var result = service.run(new EvaluationRunRequest(
            "threshold-weight-test",
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE
        ));

        assertThat(result.run().status()).isEqualTo(EvaluationRunStatus.FAILED);
        assertThat(result.run().overallScore()).isEqualTo(0.5625d);
        assertThat(result.report().failedMetrics()).containsExactly(MemoryReuseClosedLoopEvaluator.METRIC_NAME);
        assertThat(result.report().recommendedFixes()).hasSize(1);
        assertThat(result.report().recommendedFixes().getFirst())
            .contains(MemoryReuseClosedLoopEvaluator.METRIC_NAME)
            .contains("below threshold");
        assertThat(result.report().caseSummary())
            .extracting(summary -> summary.get("status"))
            .containsExactly(EvaluationCaseStatus.FAILED.name(), EvaluationCaseStatus.FAILED.name());
        assertThat(result.report().metricSummary().get(PlannerDecisionAccuracyEvaluator.METRIC_NAME))
            .containsEntry("failed", 0L);
        assertThat(result.report().metricSummary().get(MemoryReuseClosedLoopEvaluator.METRIC_NAME))
            .containsEntry("failed", 1L);
    }

    private AgentEvaluationResult runRegressionSuite() {
        return applicationService.run(new EvaluationRunRequest(
            EvaluationDatasetRegistry.REGRESSION_SUITE_DATASET,
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE
        ));
    }

    private void assertPassedRegressionRun(AgentEvaluationResult result) {
        assertThat(result.run().datasetName()).isEqualTo(EvaluationDatasetRegistry.REGRESSION_SUITE_DATASET);
        assertThat(result.run().datasetVersion()).isEqualTo("2026-07-07");
        assertThat(result.run().profile()).isEqualTo("ci-deterministic");
        assertThat(result.run().providerMode()).isEqualTo(EvaluationProviderMode.DETERMINISTIC_FAKE);
        assertThat(result.run().status())
            .as(result.report().humanReadableSummary()
                + " fixes=" + result.report().recommendedFixes()
                + " metrics=" + result.report().metricSummary())
            .isEqualTo(EvaluationRunStatus.PASSED);
        assertThat(result.run().overallScore()).isEqualTo(1.0d);
        assertThat(result.run().startedAt()).isNotNull();
        assertThat(result.run().completedAt()).isNotNull();
        assertThat(result.report().runSummary())
            .containsEntry("datasetName", EvaluationDatasetRegistry.REGRESSION_SUITE_DATASET)
            .containsEntry("datasetVersion", "2026-07-07")
            .containsEntry("profile", "ci-deterministic")
            .containsEntry("providerMode", EvaluationProviderMode.DETERMINISTIC_FAKE.name())
            .containsEntry("status", EvaluationRunStatus.PASSED.name());
        assertThat(result.report().failedMetrics()).isEmpty();
        assertThat(result.report().recommendedFixes()).isEmpty();
        assertThat(result.report().metricSummary()).containsOnlyKeys(
            PlannerDecisionAccuracyEvaluator.METRIC_NAME,
            ToolSelectionPolicyValidityEvaluator.METRIC_NAME,
            ContextCitationUsefulnessEvaluator.METRIC_NAME,
            FailureClassificationAccuracyEvaluator.METRIC_NAME,
            TestCaseCoverageEvaluator.METRIC_NAME,
            ReportUsefulnessNoSecretEvaluator.METRIC_NAME,
            MemoryReuseClosedLoopEvaluator.METRIC_NAME
        );
        assertThat(result.report().caseSummary())
            .extracting(summary -> summary.get("fixtureId"))
            .containsExactly(
                "regression-planner-continue",
                "regression-policy-validation-allowed",
                "regression-context-citation-payment-auth",
                "regression-failure-auth-401",
                "regression-case-coverage-single-order",
                "regression-report-usefulness-auth-failure",
                "regression-memory-reuse-payment-auth-loop"
            );
    }

    private GoldenTaskFixture stubFixture(
        String fixtureId,
        String metricName,
        double score,
        double threshold,
        boolean passed
    ) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of(metricName),
            "Threshold and weighting fixture " + fixtureId,
            EvaluationFixtureType.SMOKE,
            Map.of("metricName", metricName, "threshold", threshold),
            Map.of("metricName", metricName, "score", score, "threshold", threshold, "passed", passed)
        );
    }

    private static final class StaticDatasetRegistry extends EvaluationDatasetRegistry {
        private final EvaluationDataset dataset;

        private StaticDatasetRegistry(EvaluationDataset dataset) {
            this.dataset = dataset;
        }

        @Override
        public EvaluationDataset load(String datasetName) {
            return dataset;
        }
    }

    private static final class StubEvaluator implements AgentEvaluationEvaluator {

        @Override
        public String capability() {
            return "stub-threshold-weight";
        }

        @Override
        public boolean supports(GoldenTaskFixture fixture) {
            return true;
        }

        @Override
        public EvaluationCaseResult evaluate(
            EvaluationDataset dataset,
            GoldenTaskFixture fixture,
            EvaluationRunContext context
        ) {
            var setup = fixture.setupMetadata();
            var metricName = String.valueOf(setup.get("metricName"));
            var score = ((Number) setup.get("score")).doubleValue();
            var threshold = ((Number) setup.get("threshold")).doubleValue();
            var passed = Boolean.parseBoolean(String.valueOf(setup.get("passed")));
            var metric = new EvaluationMetricResult(
                metricName,
                score,
                threshold,
                dataset.weightFor(metricName),
                passed,
                Map.of("score", score, "threshold", threshold),
                fixture.expectedResults(),
                passed ? "above threshold" : "below threshold"
            );
            if (passed) {
                return EvaluationCaseResult.passed(
                    fixture.fixtureId(),
                    fixture.capabilityTags(),
                    "stub metric passed",
                    fixture.expectedResults().toString(),
                    List.of(metric)
                );
            }
            return EvaluationCaseResult.failed(
                fixture.fixtureId(),
                fixture.capabilityTags(),
                "stub metric failed",
                fixture.expectedResults().toString(),
                List.of("below threshold"),
                List.of(metric)
            );
        }
    }
}
