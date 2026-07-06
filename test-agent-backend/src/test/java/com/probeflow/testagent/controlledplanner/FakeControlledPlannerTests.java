package com.probeflow.testagent.controlledplanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeControlledPlannerTests {

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final PlannerInputFactory inputFactory = new PlannerInputFactory(new PlannerSafeToolCatalogService(registry, policyService));

    @Test
    void returnsDeterministicContinueDecisionWithoutLlmOrNetwork() {
        var decision = new FakeControlledPlanner().plan(input(FakePlannerScenario.CONTINUE));

        assertThat(decision.action()).isEqualTo(PlannerAction.CONTINUE);
        assertThat(decision.reasoning()).contains("continue");
        assertThat(decision.fakeProvider()).isFalse();
    }

    @Test
    void returnsDeterministicInsertStepDecision() {
        var decision = new FakeControlledPlanner(FakePlannerScenario.INSERT_STEP).plan(input(null));

        assertThat(decision.action()).isEqualTo(PlannerAction.INSERT_STEP);
        assertThat(decision.proposedToolName()).isEqualTo("testcase.generate-drafts");
        assertThat(decision.proposedPlanStep().stepType()).isEqualTo("GENERATE_CASES");
    }

    @Test
    void returnsDeterministicReplanDecisionWithLastOutcomeBlockers() {
        var decision = new FakeControlledPlanner(FakePlannerScenario.REPLAN).plan(input(null));

        assertThat(decision.action()).isEqualTo(PlannerAction.REPLAN);
        assertThat(decision.blockers()).contains("State changed after last step");
    }

    @Test
    void returnsDeterministicWaitForHumanDecisionWithRequiredInput() {
        var decision = new FakeControlledPlanner(FakePlannerScenario.WAIT_FOR_HUMAN).plan(input(null));

        assertThat(decision.action()).isEqualTo(PlannerAction.WAIT_FOR_HUMAN);
        assertThat(decision.requiredHumanInput().question()).contains("environment");
        assertThat(decision.requiredHumanInput().inputSchema())
            .extracting(HumanInputField::name)
            .containsExactly("targetEnvironment");
    }

    @Test
    void returnsDeterministicStopDecision() {
        var decision = new FakeControlledPlanner(FakePlannerScenario.STOP).plan(input(null));

        assertThat(decision.action()).isEqualTo(PlannerAction.STOP);
        assertThat(decision.proposed()).isTrue();
    }

    @Test
    void canSelectScenarioFromPlannerInputConstraint() {
        var planner = new FakeControlledPlanner(FakePlannerScenario.CONTINUE);
        var decision = planner.plan(input(FakePlannerScenario.WAIT_FOR_HUMAN));

        assertThat(decision.action()).isEqualTo(PlannerAction.WAIT_FOR_HUMAN);
    }

    @Test
    void canReturnFixedDecisionForStableTestFixtures() {
        var fixed = PlanDecision.stop("Fixture stop.", 0.7d, com.probeflow.testagent.agentpolicy.ToolRiskLevel.LOW, List.of("fixture"));
        var decision = new FakeControlledPlanner(fixed).plan(input(null));

        assertThat(decision).isSameAs(fixed);
    }

    @Test
    void canSimulatePlannerFailureAndMalformedOutput() {
        assertThatThrownBy(() -> new FakeControlledPlanner(FakePlannerScenario.FAILURE).plan(input(null)))
            .isInstanceOf(ControlledPlannerException.class)
            .hasMessageContaining("simulated failure");

        var malformed = new FakeControlledPlanner(FakePlannerScenario.MALFORMED).plan(input(null));

        assertThat(malformed.failed()).isTrue();
        assertThat(malformed.blockers()).containsExactly("Malformed planner output");
    }

    private PlannerInput input(FakePlannerScenario scenario) {
        var constraints = scenario == null
            ? List.<PlannerConstraint>of()
            : List.of(PlannerConstraint.of(FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE, scenario.name()));
        return inputFactory.build(new PlannerInputRequest(
            PlannerTaskState.of("task-fake", "API_TEST", "ANALYZING", "step-1", List.of("GENERATE_CASES")),
            AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.ANY),
            null,
            ContextBundleSummary.of("Compact context", List.of("wiki/fake"), 1, 0, 200),
            constraints
        ));
    }
}
