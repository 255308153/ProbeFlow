package com.probeflow.testagent.controlledplanner;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ControlledPlannerServiceTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final PlannerInputFactory inputFactory = new PlannerInputFactory(new PlannerSafeToolCatalogService(registry, policyService));

    @Test
    void serviceReturnsContinueInsertReplanWaitAndStopSuggestionsFromFakePlanner() {
        assertThat(plan(FakePlannerScenario.CONTINUE).action()).isEqualTo(PlannerAction.CONTINUE);
        assertThat(plan(FakePlannerScenario.INSERT_STEP).action()).isEqualTo(PlannerAction.INSERT_STEP);
        assertThat(plan(FakePlannerScenario.REPLAN).action()).isEqualTo(PlannerAction.REPLAN);
        assertThat(plan(FakePlannerScenario.WAIT_FOR_HUMAN).action()).isEqualTo(PlannerAction.WAIT_FOR_HUMAN);
        assertThat(plan(FakePlannerScenario.STOP).action()).isEqualTo(PlannerAction.STOP);
    }

    @Test
    void waitForHumanDecisionIncludesActionableHumanInput() {
        var decision = plan(FakePlannerScenario.WAIT_FOR_HUMAN);

        assertThat(decision.requiredHumanInput()).isNotNull();
        assertThat(decision.requiredHumanInput().blocking()).isTrue();
        assertThat(decision.requiredHumanInput().question()).contains("environment");
    }

    @Test
    void lowConfidenceAndHighRiskAreReturnedAsSuggestionDataOnly() {
        var decision = plan(FakePlannerScenario.WAIT_FOR_HUMAN);

        assertThat(decision.confidence()).isLessThan(0.5d);
        assertThat(decision.riskLevel().name()).isEqualTo("HIGH");
        assertThat(decision.proposed()).isTrue();
    }

    @Test
    void plannerFailureIsNormalizedToSafeFailedDecision() {
        var decision = plan(FakePlannerScenario.FAILURE);

        assertThat(decision.failed()).isTrue();
        assertThat(decision.action()).isEqualTo(PlannerAction.STOP);
        assertThat(decision.blockers()).contains("Fake planner simulated failure");
    }

    @Test
    void serviceSourceDoesNotDependOnTaskPlanStepToolExecutionOrPersistenceWriters() throws Exception {
        var source = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/controlledplanner/ControlledPlannerService.java"
        ));

        assertThat(source)
            .doesNotContain("TaskRepository")
            .doesNotContain("PlanStepRepository")
            .doesNotContain("PlanStepRunner")
            .doesNotContain("HttpExecutionApplicationService")
            .doesNotContain("TaskMemoryService")
            .doesNotContain("ObservationRepository")
            .doesNotContain("ExecutionRecordRepository")
            .doesNotContain("ReportGenerationApplicationService");
    }

    private PlanDecision plan(FakePlannerScenario scenario) {
        return new ControlledPlannerService(new FakeControlledPlanner(scenario)).plan(input());
    }

    private PlannerInput input() {
        return inputFactory.build(new PlannerInputRequest(
            PlannerTaskState.of("task-service", "API_TEST", "ANALYZING", "step-1", List.of("GENERATE_CASES")),
            AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.ANY),
            null,
            ContextBundleSummary.of("Planner service compact context", List.of("wiki/service"), 1, 0, 200),
            List.of()
        ));
    }
}
