package com.probeflow.testagent.controlledplanner;

import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import java.util.List;

public class FakeControlledPlanner implements ControlledPlanner {

    public static final String SCENARIO_CONSTRAINT_CODE = "FAKE_PLANNER_SCENARIO";

    private final FakePlannerScenario defaultScenario;
    private final PlanDecision fixedDecision;

    public FakeControlledPlanner() {
        this(FakePlannerScenario.CONTINUE, null);
    }

    public FakeControlledPlanner(FakePlannerScenario defaultScenario) {
        this(defaultScenario, null);
    }

    public FakeControlledPlanner(PlanDecision fixedDecision) {
        this(FakePlannerScenario.CONTINUE, fixedDecision);
    }

    private FakeControlledPlanner(FakePlannerScenario defaultScenario, PlanDecision fixedDecision) {
        this.defaultScenario = defaultScenario == null ? FakePlannerScenario.CONTINUE : defaultScenario;
        this.fixedDecision = fixedDecision;
    }

    @Override
    public PlanDecision plan(PlannerInput input) {
        if (input == null) {
            throw new IllegalArgumentException("planner input is required");
        }
        if (fixedDecision != null) {
            return fixedDecision;
        }
        return decisionFor(scenario(input), input);
    }

    private FakePlannerScenario scenario(PlannerInput input) {
        return input.constraints().stream()
            .filter(constraint -> SCENARIO_CONSTRAINT_CODE.equals(constraint.code()))
            .findFirst()
            .map(PlannerConstraint::description)
            .map(this::parseScenario)
            .orElse(defaultScenario);
    }

    private FakePlannerScenario parseScenario(String value) {
        try {
            return FakePlannerScenario.valueOf(value.trim().toUpperCase());
        } catch (RuntimeException exception) {
            return defaultScenario;
        }
    }

    private PlanDecision decisionFor(FakePlannerScenario scenario, PlannerInput input) {
        return switch (scenario) {
            case CONTINUE -> PlanDecision.continuePlan(
                "Fake planner determined the existing task plan can continue.",
                0.93d
            );
            case INSERT_STEP -> PlanDecision.insertStep(
                "Fake planner found missing draft generation before execution.",
                0.84d,
                ToolRiskLevel.MEDIUM,
                "testcase.generate-drafts",
                ProposedPlanStep.of(
                    "GENERATE_CASES",
                    "Generate missing test case drafts",
                    "Create draft test cases before execution can proceed.",
                    "testcase.generate-drafts"
                )
            );
            case REPLAN -> PlanDecision.replan(
                "Fake planner detected blockers that require a new remaining plan.",
                0.61d,
                ToolRiskLevel.MEDIUM,
                input.lastStepOutcome().blockers().isEmpty()
                    ? List.of("State changed after last step")
                    : input.lastStepOutcome().blockers()
            );
            case WAIT_FOR_HUMAN -> PlanDecision.waitForHuman(
                "Fake planner cannot choose a target environment safely.",
                0.34d,
                ToolRiskLevel.HIGH,
                RequiredHumanInput.blocking(
                    "Missing target environment",
                    "Which configured environment should the next suggested step use?",
                    List.of(HumanInputField.required("targetEnvironment", "string", "Configured target environment name."))
                )
            );
            case STOP -> PlanDecision.stop(
                "Fake planner determined the task should stop.",
                0.88d,
                ToolRiskLevel.LOW,
                List.of("No further planning action is required")
            );
            case FAILURE -> throw new ControlledPlannerException("Fake planner simulated failure");
            case MALFORMED -> PlanDecision.failed(
                "Fake planner simulated malformed structured output.",
                List.of("Malformed planner output")
            );
        };
    }
}
