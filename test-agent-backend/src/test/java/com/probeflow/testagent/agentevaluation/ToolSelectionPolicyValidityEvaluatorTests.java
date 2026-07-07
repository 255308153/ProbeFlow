package com.probeflow.testagent.agentevaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import com.probeflow.testagent.policyvalidator.PolicyValidatorService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSelectionPolicyValidityEvaluatorTests {

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final PolicyValidatorService validator = new PolicyValidatorService(registry, policyService);
    private final ToolSelectionPolicyValidityEvaluator evaluator = new ToolSelectionPolicyValidityEvaluator(
        registry,
        policyService,
        validator
    );
    private final EvaluationDatasetRegistry datasets = new EvaluationDatasetRegistry();

    @Test
    void applicationServiceRunsToolPolicyDatasetThroughRealPolicyValidator() {
        var service = new AgentEvaluationApplicationService(
            datasets,
            List.of(
                new SmokeEvaluationEvaluator(),
                evaluator
            )
        );

        var result = service.run(new EvaluationRunRequest(
            EvaluationDatasetRegistry.TOOL_POLICY_DATASET,
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE
        ));

        assertThat(result.run().status()).isEqualTo(EvaluationRunStatus.PASSED);
        assertThat(result.report().metricSummary()).containsKey(ToolSelectionPolicyValidityEvaluator.METRIC_NAME);
        assertThat(result.report().caseSummary())
            .extracting(summary -> summary.get("fixtureId"))
            .containsExactly(
                "tool-policy-allowed",
                "tool-policy-human-confirmation",
                "tool-policy-unknown",
                "tool-policy-not-visible",
                "tool-policy-not-whitelisted",
                "tool-policy-missing-precondition",
                "tool-policy-v1-boundary"
            );
    }

    @Test
    void reportsToolPolicyMismatchWithActualStatusReasonAndDiagnostic() {
        var result = evaluator.evaluate(
            datasets.load(EvaluationDatasetRegistry.TOOL_POLICY_DATASET),
            fixture(
                "tool-policy-mismatch",
                Map.of(
                    "toolName", "knowledge.retrieve-context",
                    "taskPhase", "CONTEXT_BUILDING",
                    "toolInput", Map.of("taskId", "task-1", "query", "auth boundary"),
                    "satisfiedPreconditions", List.of("TASK_EXISTS")
                ),
                Map.of(
                    "toolName", "knowledge.retrieve-context",
                    "policyStatus", "BLOCKED",
                    "policyReason", "TOOL_NOT_VISIBLE"
                )
            ),
            new EvaluationRunContext("eval-tool", "ci-deterministic", EvaluationProviderMode.DETERMINISTIC_FAKE, "fixture-eval-tool")
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.FAILED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> {
                assertThat(metric.passed()).isFalse();
                assertThat(metric.actual())
                    .containsEntry("toolName", "knowledge.retrieve-context")
                    .containsEntry("policyStatus", "ALLOWED")
                    .containsEntry("policyReason", "TOOL_ALLOWED_BY_POLICY");
                assertThat(metric.diagnosticMessage())
                    .contains("policy status mismatch")
                    .contains("policy rejection reason mismatch");
            });
    }

    @Test
    void coversBlockedRequiresHumanUnknownNotVisibleMissingPreconditionAndV1BoundaryOutcomes() {
        var dataset = datasets.load(EvaluationDatasetRegistry.TOOL_POLICY_DATASET);

        assertThat(dataset.fixtures())
            .allSatisfy(fixture -> {
                var result = evaluator.evaluate(
                    dataset,
                    fixture,
                    new EvaluationRunContext("eval-tool", "ci-deterministic", EvaluationProviderMode.DETERMINISTIC_FAKE, "fixture-eval-tool")
                );
                assertThat(result.status()).as(fixture.fixtureId()).isEqualTo(EvaluationCaseStatus.PASSED);
            });
    }

    private GoldenTaskFixture fixture(String fixtureId, Map<String, Object> setup, Map<String, Object> expected) {
        return new GoldenTaskFixture(
            fixtureId,
            List.of("policy-validation"),
            "Tool policy evaluator test fixture.",
            EvaluationFixtureType.TOOL_POLICY,
            expected,
            setup
        );
    }
}
