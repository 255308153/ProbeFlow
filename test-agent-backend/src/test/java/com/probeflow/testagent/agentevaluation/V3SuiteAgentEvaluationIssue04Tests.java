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
class V3SuiteAgentEvaluationIssue04Tests {

    @Autowired
    private AgentEvaluationApplicationService applicationService;

    @Autowired
    private EvaluationDatasetRegistry datasets;

    @Test
    void loadsV3SuiteDatasetWithCapabilityTagsThresholdsAndRiskWeightedMetrics() {
        var dataset = datasets.load(EvaluationDatasetRegistry.V3_SUITE_AGENT_DATASET);

        assertThat(dataset.name()).isEqualTo("v3-phase-6-suite-agent-capability");
        assertThat(dataset.version()).isEqualTo("2026-07-08");
        assertThat(dataset.fixtures())
            .filteredOn(fixture -> fixture.fixtureId().equals("v3-suite-agent-order-payment-loop"))
            .singleElement()
            .satisfies(fixture -> {
            assertThat(fixture.fixtureType()).isEqualTo(EvaluationFixtureType.V3_SUITE_AGENT);
            assertThat(fixture.capabilityTags()).contains(
                "suite-dependency",
                "variable-audit",
                "failure-analysis",
                "memory-feedback",
                "harness-demo",
                "redaction"
            );
            assertThat(fixture.setupMetadata().toString())
                .doesNotContain("Bearer real-secret-token")
                .doesNotContain("sk_live_order_secret");
        });
        assertThat(dataset.metricThresholds()).containsOnlyKeys(
            V3SuiteAgentCapabilityEvaluator.DEPENDENCY_COVERAGE_METRIC,
            V3SuiteAgentCapabilityEvaluator.VARIABLE_AUDIT_METRIC,
            V3SuiteAgentCapabilityEvaluator.FAILURE_ANALYSIS_METRIC,
            V3SuiteAgentCapabilityEvaluator.MEMORY_CANDIDATE_METRIC,
            V3SuiteAgentCapabilityEvaluator.HARNESS_COMPLETENESS_METRIC,
            V3SuiteAgentCapabilityEvaluator.SECRET_REDACTION_METRIC,
            MemoryReuseClosedLoopEvaluator.V3_SUITE_METRIC_NAME
        );
        assertThat(dataset.metricWeights().get(V3SuiteAgentCapabilityEvaluator.FAILURE_ANALYSIS_METRIC))
            .isGreaterThan(dataset.metricWeights().get(V3SuiteAgentCapabilityEvaluator.HARNESS_COMPLETENESS_METRIC));
        assertThat(dataset.metricWeights().get(V3SuiteAgentCapabilityEvaluator.MEMORY_CANDIDATE_METRIC))
            .isGreaterThan(dataset.metricWeights().get(V3SuiteAgentCapabilityEvaluator.HARNESS_COMPLETENESS_METRIC));
        assertThat(dataset.metricWeights().get(V3SuiteAgentCapabilityEvaluator.SECRET_REDACTION_METRIC))
            .isGreaterThan(dataset.metricWeights().get(V3SuiteAgentCapabilityEvaluator.HARNESS_COMPLETENESS_METRIC));
    }

    @Test
    void runsV3SuiteDatasetThroughApplicationServiceWithDeterministicFakeProvider() {
        var result = applicationService.run(new EvaluationRunRequest(
            EvaluationDatasetRegistry.V3_SUITE_AGENT_DATASET,
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE
        ));

        assertThat(result.run().status())
            .as(result.report().humanReadableSummary()
                + " fixes=" + result.report().recommendedFixes()
                + " metrics=" + result.report().metricSummary())
            .isEqualTo(EvaluationRunStatus.PASSED);
        assertThat(result.run().providerMode()).isEqualTo(EvaluationProviderMode.DETERMINISTIC_FAKE);
        assertThat(result.run().overallScore()).isEqualTo(1.0d);
        assertThat(result.report().recommendedFixes()).isEmpty();
        assertThat(result.report().metricSummary()).containsOnlyKeys(
            V3SuiteAgentCapabilityEvaluator.DEPENDENCY_COVERAGE_METRIC,
            V3SuiteAgentCapabilityEvaluator.VARIABLE_AUDIT_METRIC,
            V3SuiteAgentCapabilityEvaluator.FAILURE_ANALYSIS_METRIC,
            V3SuiteAgentCapabilityEvaluator.MEMORY_CANDIDATE_METRIC,
            V3SuiteAgentCapabilityEvaluator.HARNESS_COMPLETENESS_METRIC,
            V3SuiteAgentCapabilityEvaluator.SECRET_REDACTION_METRIC,
            MemoryReuseClosedLoopEvaluator.V3_SUITE_METRIC_NAME
        );
        assertThat(result.report().caseSummary())
            .extracting(summary -> summary.get("fixtureId"))
            .containsExactly("v3-suite-agent-order-payment-loop", "v3-suite-memory-reuse-order-payment-loop");
        assertThat(result.report().caseSummary())
            .extracting(summary -> summary.get("status"))
            .containsOnly(EvaluationCaseStatus.PASSED.name());
        assertThat(result.toString())
            .doesNotContain("Bearer real-secret-token")
            .doesNotContain("sk_live_order_secret")
            .doesNotContain("cookie-value");

        var defaultResult = applicationService.runDefaultDataset();
        assertThat(defaultResult.run().providerMode()).isEqualTo(EvaluationProviderMode.DETERMINISTIC_FAKE);
        assertThat(defaultResult.report().runSummary()).containsEntry("datasetName", EvaluationDatasetRegistry.SMOKE_DATASET);
    }

    @Test
    void failedV3SuiteMetricProducesRecommendedFixesWithSpecificCapabilityGap() {
        var dataset = brokenDataset();
        var service = new AgentEvaluationApplicationService(
            new StaticDatasetRegistry(dataset),
            List.of(new V3SuiteAgentCapabilityEvaluator())
        );

        var result = service.run(new EvaluationRunRequest(
            dataset.name(),
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE
        ));

        assertThat(result.run().status()).isEqualTo(EvaluationRunStatus.FAILED);
        assertThat(result.report().failedMetrics()).contains(
            V3SuiteAgentCapabilityEvaluator.MEMORY_CANDIDATE_METRIC,
            V3SuiteAgentCapabilityEvaluator.SECRET_REDACTION_METRIC
        );
        assertThat(result.report().recommendedFixes())
            .anySatisfy(fix -> assertThat(fix)
                .contains(V3SuiteAgentCapabilityEvaluator.MEMORY_CANDIDATE_METRIC)
                .contains("missing memory evidence"))
            .anySatisfy(fix -> assertThat(fix)
                .contains(V3SuiteAgentCapabilityEvaluator.SECRET_REDACTION_METRIC)
                .contains("sensitive key retained"));
    }

    private EvaluationDataset brokenDataset() {
        return new EvaluationDataset(
            "v3-suite-broken-test",
            "2026-07-08",
            List.of(new GoldenTaskFixture(
                "broken-v3-suite",
                List.of("v3", "suite", "memory-feedback", "redaction"),
                "Broken V3 suite fixture for recommended fix checks.",
                EvaluationFixtureType.V3_SUITE_AGENT,
                Map.ofEntries(
                    Map.entry("expectedDependencyPairs", List.of("create-order->pay-order")),
                    Map.entry("expectedVariableWrites", List.of("suite.orderId")),
                    Map.entry("expectedVariableConsumers", List.of("pay-order:${suite.orderId}")),
                    Map.entry("expectedFailureClassification", "VARIABLE_EXTRACTION_FAILURE"),
                    Map.entry("expectedRootStep", "create-order"),
                    Map.entry("expectedAffectedDownstreamSteps", List.of("pay-order")),
                    Map.entry("expectedNextSuggestionContains", "Fix BODY_JSON extractRule"),
                    Map.entry("expectedMemoryTags", List.of("v3", "suite", "memory-feedback")),
                    Map.entry("expectedMemoryConfidenceMin", 0.9d),
                    Map.entry("expectedMemoryEvidence", List.of("failedVariable=suite.orderId")),
                    Map.entry("expectedHarnessSections", List.of("generated-suite-draft"))
                ),
                Map.of(
                    "suiteDraft", Map.of(
                        "dependencyPairs", List.of("create-order->pay-order"),
                        "extractRules", List.of(Map.of("id", "extract-order-id", "targetKey", "orderId")),
                        "variableReferences", List.of(Map.of("expression", "${suite.orderId}"))
                    ),
                    "variableAudit", Map.of(
                        "writes", List.of("suite.orderId"),
                        "consumes", List.of("pay-order:${suite.orderId}")
                    ),
                    "failureAnalysis", Map.of(
                        "classification", "VARIABLE_EXTRACTION_FAILURE",
                        "rootStep", "create-order",
                        "affectedDownstreamSteps", List.of("pay-order"),
                        "nextSuggestion", "Fix BODY_JSON extractRule before retrying."
                    ),
                    "memoryCandidate", Map.of(
                        "sourceRef", "broken-source",
                        "tags", List.of("v3", "suite", "memory-feedback"),
                        "confidence", 0.95d,
                        "evidence", List.of(),
                        "applicableWhen", "order id extraction changes",
                        "content", "candidate content",
                        "Authorization", "Bearer real-secret-token"
                    ),
                    "harness", Map.of("sections", Map.of("generated-suite-draft", "REAL"))
                )
            )),
            0.8d,
            Map.of(
                V3SuiteAgentCapabilityEvaluator.DEPENDENCY_COVERAGE_METRIC, 0.8d,
                V3SuiteAgentCapabilityEvaluator.VARIABLE_AUDIT_METRIC, 0.8d,
                V3SuiteAgentCapabilityEvaluator.FAILURE_ANALYSIS_METRIC, 0.8d,
                V3SuiteAgentCapabilityEvaluator.MEMORY_CANDIDATE_METRIC, 0.8d,
                V3SuiteAgentCapabilityEvaluator.HARNESS_COMPLETENESS_METRIC, 0.8d,
                V3SuiteAgentCapabilityEvaluator.SECRET_REDACTION_METRIC, 1.0d
            ),
            Map.of(
                V3SuiteAgentCapabilityEvaluator.DEPENDENCY_COVERAGE_METRIC, 1.0d,
                V3SuiteAgentCapabilityEvaluator.VARIABLE_AUDIT_METRIC, 1.0d,
                V3SuiteAgentCapabilityEvaluator.FAILURE_ANALYSIS_METRIC, 2.0d,
                V3SuiteAgentCapabilityEvaluator.MEMORY_CANDIDATE_METRIC, 2.5d,
                V3SuiteAgentCapabilityEvaluator.HARNESS_COMPLETENESS_METRIC, 1.0d,
                V3SuiteAgentCapabilityEvaluator.SECRET_REDACTION_METRIC, 2.5d
            ),
            Map.of("broken-v3-suite", 0.8d)
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
}
