package com.probeflow.testagent.policyvalidator;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.controlledplanner.ContextBundleSummary;
import com.probeflow.testagent.controlledplanner.HumanInputField;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlanDecisionStatus;
import com.probeflow.testagent.controlledplanner.PlannerAction;
import com.probeflow.testagent.controlledplanner.PlannerInput;
import com.probeflow.testagent.controlledplanner.PlannerInputFactory;
import com.probeflow.testagent.controlledplanner.PlannerInputRequest;
import com.probeflow.testagent.controlledplanner.PlannerTaskState;
import com.probeflow.testagent.controlledplanner.ProposedPlanStep;
import com.probeflow.testagent.controlledplanner.RequiredHumanInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyValidatorDecisionSafetyTests {

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final PlannerInputFactory inputFactory = new PlannerInputFactory(
        new PlannerSafeToolCatalogService(registry, policyService)
    );
    private final PolicyValidatorService validator = new PolicyValidatorService();

    @Test
    void blockedPlannerDecisionBecomesBlockedValidationAndPreservesPlannerBlockers() {
        var decision = PlanDecision.blocked("Planner found unsafe path", List.of("missing api spec", "unsafe tool"));

        var result = validate(decision);

        assertThat(result.status()).isEqualTo(PolicyValidationStatus.BLOCKED);
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.PLANNER_BLOCKED);
        assertThat(result.blockers()).containsExactly("missing api spec", "unsafe tool");
    }

    @Test
    void failedPlannerDecisionIsBlockedBeforeAnyExecutionLayerCanConsumeIt() {
        var decision = PlanDecision.failed("Planner JSON could not be parsed", List.of("invalid action"));

        var result = validate(decision);

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.PLANNER_FAILED);
        assertThat(result.blockers()).containsExactly("invalid action");
    }

    @Test
    void waitForHumanRequiresConfirmationWhenItContainsActionableInput() {
        var humanInput = RequiredHumanInput.blocking(
            "Environment is ambiguous",
            "Which target environment should be used?",
            List.of(HumanInputField.required("targetEnvironment", "string", "Configured environment name."))
        );
        var decision = PlanDecision.waitForHuman("Need target environment", 0.45d, ToolRiskLevel.HIGH, humanInput);

        var result = validate(decision);

        assertThat(result.requiresHumanConfirmation()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.HUMAN_INPUT_REQUIRED);
        assertThat(result.requiredHumanInput()).isEqualTo(humanInput);
        assertThat(result.auditSummary()).containsEntry("requiresHumanInput", true);
    }

    @Test
    void waitForHumanWithoutActionableInputIsBlocked() {
        var decision = new PlanDecision(
            null,
            PlanDecisionStatus.PROPOSED,
            PlannerAction.WAIT_FOR_HUMAN,
            "Ask user but forgot the question.",
            0.5d,
            ToolRiskLevel.MEDIUM,
            null,
            null,
            null,
            List.of(),
            null,
            false
        );

        var result = validate(decision);

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.MISSING_HUMAN_INPUT);
        assertThat(result.blockers()).containsExactly("MISSING_HUMAN_INPUT");
    }

    @Test
    void insertStepWithoutProposedPlanStepIsBlocked() {
        var decision = PlanDecision.insertStep("Need another step", 0.7d, ToolRiskLevel.LOW, "knowledge.retrieve-context", null);

        var result = validate(decision);

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.MISSING_PROPOSED_STEP);
        assertThat(result.blockers()).containsExactly("MISSING_PROPOSED_STEP");
    }

    @Test
    void replanStopAndContinueAreSafeSignalsWhenTheyDoNotCarryToolIntent() {
        assertThat(validate(PlanDecision.continuePlan("Continue current deterministic plan", 0.9d)).reasonCode())
            .isEqualTo(PolicyValidationReasonCode.SAFE_CONTINUE);
        assertThat(validate(PlanDecision.replan("Need a different route", 0.7d, ToolRiskLevel.MEDIUM, List.of("new failure"))).reasonCode())
            .isEqualTo(PolicyValidationReasonCode.SAFE_REPLAN);
        assertThat(validate(PlanDecision.stop("Task complete", 0.99d, ToolRiskLevel.LOW, List.of())).reasonCode())
            .isEqualTo(PolicyValidationReasonCode.SAFE_STOP);
    }

    @Test
    void stopCannotCarryToolExecutionIntent() {
        var decision = new PlanDecision(
            null,
            PlanDecisionStatus.PROPOSED,
            PlannerAction.STOP,
            "Stop but also execute HTTP",
            0.8d,
            ToolRiskLevel.HIGH,
            "http.execute-approved-case",
            ProposedPlanStep.of("EXECUTE_CASE", "Execute approved case", "Unsafe mixed intent.", "http.execute-approved-case"),
            null,
            List.of(),
            null,
            false
        );

        var result = validate(decision);

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.STOP_WITH_TOOL_INTENT);
    }

    private PolicyValidationResult validate(PlanDecision decision) {
        return validator.validate(new PolicyValidationRequest(decision, input(), AgentPolicy.v2Phase2Default()));
    }

    private PlannerInput input() {
        return inputFactory.build(new PlannerInputRequest(
            PlannerTaskState.of("task-policy-action", "API_TEST", "ANALYZING", "step-1", List.of("CONTINUE")),
            AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.ANY),
            null,
            ContextBundleSummary.of("Policy action validation context", List.of("wiki/action"), 1, 0, 200),
            List.of()
        ));
    }
}
