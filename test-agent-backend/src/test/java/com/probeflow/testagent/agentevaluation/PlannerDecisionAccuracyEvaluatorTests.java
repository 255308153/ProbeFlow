package com.probeflow.testagent.agentevaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.controlledplanner.FakeControlledPlanner;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlannerDecisionAccuracyEvaluatorTests {

    private final EvaluationDatasetRegistry datasets = new EvaluationDatasetRegistry();

    @Test
    void applicationServiceRunsPlannerDatasetThroughFakePlanner() {
        var service = new AgentEvaluationApplicationService(
            datasets,
            List.of(new SmokeEvaluationEvaluator(), new PlannerDecisionAccuracyEvaluator(new FakeControlledPlanner()))
        );

        var result = service.run(new EvaluationRunRequest(
            EvaluationDatasetRegistry.PLANNER_DATASET,
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE
        ));

        assertThat(result.run().status()).isEqualTo(EvaluationRunStatus.PASSED);
        assertThat(result.run().overallScore()).isEqualTo(1.0d);
        assertThat(result.report().metricSummary()).containsKey(PlannerDecisionAccuracyEvaluator.METRIC_NAME);
        assertThat(result.report().caseSummary())
            .extracting(summary -> summary.get("fixtureId"))
            .containsExactly(
                "planner-continue",
                "planner-insert-step",
                "planner-replan",
                "planner-wait-for-human",
                "planner-stop"
            );
    }

    @Test
    void reportsPlannerActionMismatchWithoutInspectingPromptText() {
        var result = evaluate(
            new FakeControlledPlanner(),
            fixture(
                "planner-action-mismatch",
                "CONTINUE",
                Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "REPLAN",
                    "confidenceMin", 0.9d,
                    "confidenceMax", 1.0d,
                    "riskLevel", "LOW"
                )
            )
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.FAILED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> {
                assertThat(metric.passed()).isFalse();
                assertThat(metric.diagnosticMessage()).contains("action mismatch");
                assertThat(metric.actual()).containsEntry("plannerAction", "CONTINUE");
                assertThat(metric.expected()).containsEntry("plannerAction", "REPLAN");
            });
    }

    @Test
    void evaluatesWaitForHumanRequiredInputSchema() {
        var result = evaluate(
            new FakeControlledPlanner(),
            fixture(
                "planner-human-input",
                "WAIT_FOR_HUMAN",
                Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "WAIT_FOR_HUMAN",
                    "riskLevel", "HIGH",
                    "requiredHumanInputFields", List.of("targetEnvironment")
                )
            )
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.PASSED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> assertThat(metric.actual())
                .containsEntry("requiredHumanInputFields", List.of("targetEnvironment")));
    }

    @Test
    void identifiesBlockedAndFailedPlannerDecisionsWithReadableDiagnostics() {
        var blocked = evaluate(
            new FakeControlledPlanner(PlanDecision.blocked("Need human scope", List.of("Missing environment"))),
            fixture(
                "planner-blocked",
                "CONTINUE",
                Map.of(
                    "plannerStatus", "BLOCKED",
                    "plannerAction", "STOP",
                    "riskLevel", "HIGH",
                    "blockers", List.of("Missing environment")
                )
            )
        );
        var failed = evaluate(
            new FakeControlledPlanner(),
            fixture(
                "planner-failed",
                "MALFORMED",
                Map.of(
                    "plannerStatus", "PROPOSED",
                    "plannerAction", "STOP",
                    "riskLevel", "HIGH"
                )
            )
        );

        assertThat(blocked.status()).isEqualTo(EvaluationCaseStatus.PASSED);
        assertThat(blocked.actualSummary()).contains("BLOCKED");
        assertThat(failed.status()).isEqualTo(EvaluationCaseStatus.FAILED);
        assertThat(failed.metricResults()).singleElement()
            .satisfies(metric -> assertThat(metric.diagnosticMessage()).contains("planner failed decision"));
    }

    private EvaluationCaseResult evaluate(FakeControlledPlanner planner, GoldenTaskFixture fixture) {
        var evaluator = new PlannerDecisionAccuracyEvaluator(planner);
        return evaluator.evaluate(
            datasets.load(EvaluationDatasetRegistry.PLANNER_DATASET),
            fixture,
            new EvaluationRunContext("eval-test", "ci-deterministic", EvaluationProviderMode.DETERMINISTIC_FAKE, "fixture-eval-test")
        );
    }

    private GoldenTaskFixture fixture(String fixtureId, String scenario, Map<String, Object> expected) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of("planner-decision"),
            "Planner evaluator test fixture.",
            EvaluationFixtureType.PLANNER_DECISION,
            expected,
            Map.of("fakePlannerScenario", scenario)
        );
    }
}
