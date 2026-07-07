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
class TestCaseCoverageEvaluatorTests {

    @Autowired
    private TestCaseCoverageEvaluator evaluator;

    @Test
    void applicationServiceRunsCaseCoverageDatasetThroughGeneratedDrafts() {
        var service = new AgentEvaluationApplicationService(
            new EvaluationDatasetRegistry(),
            List.of(evaluator)
        );

        var result = service.run(new EvaluationRunRequest(
            EvaluationDatasetRegistry.CASE_COVERAGE_DATASET,
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE
        ));

        assertThat(result.run().status())
            .as(result.report().humanReadableSummary() + " fixes=" + result.report().recommendedFixes())
            .isEqualTo(EvaluationRunStatus.PASSED);
        assertThat(result.report().metricSummary()).containsKey(TestCaseCoverageEvaluator.METRIC_NAME);
        assertThat(result.report().caseSummary())
            .extracting(summary -> summary.get("fixtureId"))
            .containsExactly("case-coverage-single-order", "case-coverage-suite-flow");
    }

    @Test
    void reportsMissingCoverageAndDuplicatePenaltyDiagnostics() {
        var result = evaluator.evaluate(
            dataset(),
            fixture(
                "case-coverage-diagnostic",
                Map.of("testAssets", List.of(
                    asset(
                        "asset-1",
                        "HAPPY_PATH",
                        "Create order happy path",
                        List.of("api", "happy-path"),
                        201,
                        Map.of("body", Map.of("skuId", "SKU-1", "quantity", 1)),
                        List.of(Map.of("order", 1, "apiSpecId", "api-1", "expectedStatus", 201)),
                        List.of(Map.of("field", "status", "operator", "equals", "expected", "CREATED")),
                        "dup-key"
                    ),
                    asset(
                        "asset-2",
                        "HAPPY_PATH",
                        "Create order happy path duplicate",
                        List.of("api", "happy-path"),
                        201,
                        Map.of("body", Map.of("skuId", "SKU-1", "quantity", 1)),
                        List.of(Map.of("order", 1, "apiSpecId", "api-1", "expectedStatus", 201)),
                        List.of(Map.of("field", "status", "operator", "equals", "expected", "CREATED")),
                        "dup-key"
                    )
                )),
                Map.of(
                    "expectedCoverageCategories", List.of("happy-path", "validation-negative", "auth-negative"),
                    "requiredHappyPathScenario", "HAPPY_PATH",
                    "requiredValidationNegativeCases", List.of("MISSING_REQUIRED"),
                    "requiredAuthNegativeCases", List.of("AUTHENTICATION_FAILURE"),
                    "allowedDuplicateCount", 0
                )
            ),
            context("eval-case-coverage-diagnostics")
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.FAILED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> {
                assertThat(metric.passed()).isFalse();
                assertThat(metric.actual()).containsEntry("duplicateCount", 1);
                assertThat(metric.diagnosticMessage())
                    .contains("missing coverage category")
                    .contains("missing validation negative case")
                    .contains("missing auth negative case")
                    .contains("duplicate penalty");
            });
    }

    @Test
    void evaluatesSuiteDependencyEvidenceFromFixedAssets() {
        var result = evaluator.evaluate(
            dataset(),
            fixture(
                "case-coverage-suite-direct",
                Map.of("testAssets", List.of(
                    asset(
                        "suite-1",
                        "BUSINESS_FLOW",
                        "Create then read order",
                        List.of("suite-dependency", "business-flow"),
                        200,
                        Map.of("flow", "create-read"),
                        List.of(
                            Map.of("order", 1, "apiSpecId", "api-create", "expectedStatus", 201),
                            Map.of("order", 2, "apiSpecId", "api-read", "expectedStatus", 200)
                        ),
                        List.of(Map.of("field", "orderId", "operator", "exists")),
                        "suite-dedup"
                    )
                )),
                Map.of(
                    "expectedCoverageCategories", List.of("suite-dependency"),
                    "requiredSuiteDependencyCoverage", true,
                    "requireRequestVariationEvidence", true,
                    "requireAssertionEvidence", true,
                    "requireScenarioMetadata", true,
                    "allowedDuplicateCount", 0
                )
            ),
            context("eval-case-coverage-suite")
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.PASSED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> assertThat(metric.actual())
                .containsEntry("coverageCategories", List.of("suite-dependency")));
    }

    private GoldenTaskFixture fixture(
        String fixtureId,
        Map<String, Object> setup,
        Map<String, Object> expected
    ) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of("case-coverage"),
            "Evaluate test case coverage fixture.",
            EvaluationFixtureType.TEST_CASE_COVERAGE,
            expected,
            setup
        );
    }

    private EvaluationDataset dataset() {
        return new EvaluationDataset(
            "case-coverage-test",
            "2026-07-07",
            List.of(),
            0.8d,
            Map.of(TestCaseCoverageEvaluator.METRIC_NAME, 0.8d),
            Map.of(TestCaseCoverageEvaluator.METRIC_NAME, 2.0d),
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

    private Map<String, Object> asset(
        String assetId,
        String scenarioCategory,
        String scenarioName,
        List<String> tags,
        int expectedStatus,
        Map<String, Object> requestShape,
        List<Map<String, Object>> steps,
        List<Map<String, Object>> assertions,
        String dedupKey
    ) {
        return Map.of(
            "assetId", assetId,
            "scenarioCategory", scenarioCategory,
            "scenarioName", scenarioName,
            "tags", tags,
            "expectedStatus", expectedStatus,
            "requestShape", requestShape,
            "steps", steps,
            "assertions", assertions,
            "expectedResult", "HTTP " + expectedStatus,
            "dedupKey", dedupKey
        );
    }
}
