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
class MemoryReuseClosedLoopEvaluatorTests {

    @Autowired
    private AgentEvaluationApplicationService applicationService;

    @Autowired
    private MemoryReuseClosedLoopEvaluator evaluator;

    @Test
    void applicationServiceRunsMemoryReuseDatasetThroughClosedLoop() {
        var result = applicationService.run(new EvaluationRunRequest(
            EvaluationDatasetRegistry.MEMORY_REUSE_DATASET,
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE
        ));

        assertThat(result.run().status())
            .as(result.report().humanReadableSummary()
                + " fixes=" + result.report().recommendedFixes()
                + " metrics=" + result.report().metricSummary())
            .isEqualTo(EvaluationRunStatus.PASSED);
        assertThat(result.report().metricSummary()).containsKey(MemoryReuseClosedLoopEvaluator.METRIC_NAME);
        assertThat(result.report().caseSummary()).singleElement()
            .satisfies(summary -> assertThat(summary)
                .containsEntry("fixtureId", "memory-reuse-payment-auth-loop")
                .containsEntry("status", EvaluationCaseStatus.PASSED.name()));
    }

    @Test
    void evaluatesRecallCitationUsageFeedbackAndRepeatedFailureMerge() {
        var result = evaluator.evaluate(dataset(), fixture("memory-reuse-direct-pass", Map.of()), context("eval-memory-pass"));

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.PASSED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> {
                assertThat(metric.passed()).isTrue();
                assertThat(metric.actual())
                    .containsEntry("firstStageStatus", "ACCEPTED")
                    .containsEntry("repeatedFailureStatus", "MERGED")
                    .containsEntry("positiveRecall", true)
                    .containsEntry("positiveCitation", true)
                    .containsEntry("positiveUsageConsumer", "AGENT_EVALUATION")
                    .containsEntry("positiveFeedbackStatus", "RECORDED")
                    .containsEntry("negativeRecall", true)
                    .containsEntry("negativeCitation", true)
                    .containsEntry("negativeUsageConsumer", "AGENT_EVALUATION")
                    .containsEntry("negativeFeedbackStatus", "RECORDED");
                assertThat(metric.actual().get("memoryId")).isNotNull();
                assertThat(metric.actual().get("mergeCount")).isEqualTo(2);
                assertThat((Float) metric.actual().get("confidenceAfterPositive"))
                    .isGreaterThan((Float) metric.actual().get("confidenceBeforePositive"));
                assertThat((Float) metric.actual().get("successContributionAfterPositive"))
                    .isGreaterThan((Float) metric.actual().get("successContributionBeforePositive"));
                assertThat((Float) metric.actual().get("confidenceAfterNegative"))
                    .isLessThan((Float) metric.actual().get("confidenceBeforeNegative"));
                assertThat((Float) metric.actual().get("successContributionAfterNegative"))
                    .isLessThan((Float) metric.actual().get("successContributionBeforeNegative"));
            });
    }

    @Test
    void reportsMissingRecallCitationUsageAndFeedbackDiagnostics() {
        var result = evaluator.evaluate(
            dataset(),
            fixture("memory-reuse-diagnostics", Map.of("skipFirstStageLearning", true)),
            context("eval-memory-diagnostics")
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.FAILED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> {
                assertThat(metric.passed()).isFalse();
                assertThat(metric.diagnosticMessage())
                    .contains("missing long-term memory")
                    .contains("missing recall")
                    .contains("missing citation")
                    .contains("missing usage record")
                    .contains("feedback not applied");
            });
    }

    @Test
    void differentFixtureNamespacesKeepRepeatedRunsIsolated() {
        var first = evaluator.evaluate(dataset(), fixture("memory-reuse-isolation", Map.of()), context("eval-memory-isolation-a"));
        var second = evaluator.evaluate(dataset(), fixture("memory-reuse-isolation", Map.of()), context("eval-memory-isolation-b"));

        assertThat(first.status())
            .as(first.metricResults().getFirst().diagnosticMessage() + " actual=" + first.metricResults().getFirst().actual())
            .isEqualTo(EvaluationCaseStatus.PASSED);
        assertThat(second.status())
            .as(second.metricResults().getFirst().diagnosticMessage() + " actual=" + second.metricResults().getFirst().actual())
            .isEqualTo(EvaluationCaseStatus.PASSED);
        var firstActual = first.metricResults().getFirst().actual();
        var secondActual = second.metricResults().getFirst().actual();
        assertThat(firstActual.get("memoryId")).isNotEqualTo(secondActual.get("memoryId"));
        assertThat(firstActual.get("errorCode")).isNotEqualTo(secondActual.get("errorCode"));
    }

    private EvaluationDataset dataset() {
        return new EvaluationDataset(
            "memory-reuse-test",
            "2026-07-07",
            List.of(),
            0.8d,
            Map.of(MemoryReuseClosedLoopEvaluator.METRIC_NAME, 0.8d),
            Map.of(MemoryReuseClosedLoopEvaluator.METRIC_NAME, 2.0d),
            Map.of()
        );
    }

    private GoldenTaskFixture fixture(String fixtureId, Map<String, Object> setupOverrides) {
        var setup = new java.util.LinkedHashMap<String, Object>();
        setup.put("systemName", "order-platform");
        setup.put("moduleName", "payment");
        setup.putAll(setupOverrides);
        return new GoldenTaskFixture(
            fixtureId,
            List.of("memory-reuse"),
            "Evaluate two-stage memory reuse.",
            EvaluationFixtureType.MEMORY_REUSE,
            Map.of(
                "expectedRecall", true,
                "expectedCitation", true,
                "expectedUsageRecord", true,
                "expectedPositiveFeedbackIncrease", true,
                "expectedNegativeFeedbackDecrease", true,
                "expectedRepeatedFailureMerge", true
            ),
            setup
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
