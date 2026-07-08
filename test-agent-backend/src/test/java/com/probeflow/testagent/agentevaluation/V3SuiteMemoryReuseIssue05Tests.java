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
class V3SuiteMemoryReuseIssue05Tests {

    @Autowired
    private AgentEvaluationApplicationService applicationService;

    @Autowired
    private MemoryReuseClosedLoopEvaluator evaluator;

    @Test
    void v3SuiteDatasetRunsMemoryReuseClosedLoopWithDeterministicFakeProvider() {
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
        assertThat(result.report().metricSummary()).containsKey(MemoryReuseClosedLoopEvaluator.V3_SUITE_METRIC_NAME);
        assertThat(result.report().runSummary())
            .containsEntry("usesRealProvider", false)
            .containsEntry("stableRegression", true)
            .containsEntry("comparisonSummary", "Deterministic fake baseline is the stable CI regression signal.");
        assertThat(result.report().caseSummary())
            .extracting(summary -> summary.get("fixtureId"))
            .contains("v3-suite-memory-reuse-order-payment-loop");
    }

    @Test
    void closedLoopMetricDistinguishesGeneratedCandidateFromActualReuse() {
        var result = evaluator.evaluate(
            v3Dataset(),
            v3Fixture("v3-suite-memory-generated-not-reused", Map.of("skipReuseAfterLearning", true)),
            context("eval-v3-memory-not-reused", EvaluationProviderMode.DETERMINISTIC_FAKE)
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.FAILED);
        assertThat(result.metricResults()).singleElement().satisfies(metric -> {
            assertThat(metric.metricName()).isEqualTo(MemoryReuseClosedLoopEvaluator.V3_SUITE_METRIC_NAME);
            assertThat(metric.passed()).isFalse();
            assertThat(metric.actual())
                .containsEntry("providerMode", EvaluationProviderMode.DETERMINISTIC_FAKE.name())
                .containsEntry("usesRealProvider", false)
                .containsEntry("includedInCiRegression", true)
                .containsEntry("positiveRecall", false)
                .containsEntry("positiveCitation", false);
            assertThat(metric.actual().get("memoryId")).isNotNull();
            assertThat(metric.actual().get("sourceRef")).asString().contains("v3-suite-memory-reuse:");
            assertThat(metric.actual().get("firstStageStatus")).isIn("ACCEPTED", "MERGED", "DUPLICATE");
            assertThat(metric.diagnosticMessage())
                .contains("missing recall")
                .contains("missing citation")
                .contains("missing usage record");
        });
    }

    @Test
    void manualRealExperimentIsExplicitCiIsolatedAndDoesNotWriteLongTermMemoryByDefault() {
        var serviceResult = applicationService.run(new EvaluationRunRequest(
            EvaluationDatasetRegistry.V3_SUITE_AGENT_DATASET,
            "manual-local-comparison",
            EvaluationProviderMode.MANUAL_REAL_EXPERIMENT
        ));

        assertThat(serviceResult.report().runSummary())
            .containsEntry("providerMode", EvaluationProviderMode.MANUAL_REAL_EXPERIMENT.name())
            .containsEntry("usesRealProvider", true)
            .containsEntry("stableRegression", false);
        assertThat(serviceResult.report().runSummary().get("comparisonSummary").toString())
            .contains("isolated from deterministic CI regression");

        var direct = evaluator.evaluate(
            v3Dataset(),
            v3Fixture("v3-suite-memory-manual-real", Map.of()),
            context("eval-v3-memory-manual-real", EvaluationProviderMode.MANUAL_REAL_EXPERIMENT)
        );

        assertThat(direct.status()).isEqualTo(EvaluationCaseStatus.PASSED);
        assertThat(direct.metricResults()).singleElement().satisfies(metric -> assertThat(metric.actual())
            .containsEntry("providerMode", EvaluationProviderMode.MANUAL_REAL_EXPERIMENT.name())
            .containsEntry("usesRealProvider", true)
            .containsEntry("includedInCiRegression", false)
            .containsEntry("writesLongTermMemory", false)
            .containsEntry("requiresHumanConfirmedMemoryFeedback", true)
            .containsEntry("memoryId", null));

        var defaultRun = applicationService.runDefaultDataset();
        assertThat(defaultRun.run().providerMode()).isEqualTo(EvaluationProviderMode.DETERMINISTIC_FAKE);
        assertThat(defaultRun.report().runSummary()).containsEntry("usesRealProvider", false);
    }

    private EvaluationDataset v3Dataset() {
        return new EvaluationDataset(
            "v3-memory-reuse-test",
            "2026-07-08",
            List.of(),
            0.8d,
            Map.of(MemoryReuseClosedLoopEvaluator.V3_SUITE_METRIC_NAME, 0.8d),
            Map.of(MemoryReuseClosedLoopEvaluator.V3_SUITE_METRIC_NAME, 2.3d),
            Map.of()
        );
    }

    private GoldenTaskFixture v3Fixture(String fixtureId, Map<String, Object> setupOverrides) {
        var setup = new java.util.LinkedHashMap<String, Object>();
        setup.put("systemName", "order-platform");
        setup.put("moduleName", "v3-suite-payment-memory");
        setup.put("uses_real_llm", false);
        setup.put("uses_real_embedding", false);
        setup.put("uses_external_http", false);
        setup.putAll(setupOverrides);
        return new GoldenTaskFixture(
            fixtureId,
            List.of("v3", "suite", "memory-reuse", "closed-loop", "manual-real-boundary"),
            "Evaluate V3 suite memory reuse closed loop.",
            EvaluationFixtureType.V3_SUITE_MEMORY_REUSE,
            Map.of(
                "expectedFirstStageLearning", true,
                "expectedRepeatedFailureMerge", true,
                "expectedRecall", true,
                "expectedCitation", true,
                "expectedUsageRecord", true,
                "expectedConsumer", "AGENT_EVALUATION"
            ),
            setup
        );
    }

    private EvaluationRunContext context(String runId, EvaluationProviderMode providerMode) {
        return new EvaluationRunContext(runId, "ci-deterministic", providerMode, "fixture-" + runId);
    }
}
