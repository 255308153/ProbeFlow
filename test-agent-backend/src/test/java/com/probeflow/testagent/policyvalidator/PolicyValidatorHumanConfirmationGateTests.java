package com.probeflow.testagent.policyvalidator;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.AgentWorkflowMode;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import com.probeflow.testagent.agentpolicy.ToolPrecondition;
import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.controlledplanner.ContextBundleSummary;
import com.probeflow.testagent.controlledplanner.HumanInputField;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerInput;
import com.probeflow.testagent.controlledplanner.PlannerInputFactory;
import com.probeflow.testagent.controlledplanner.PlannerInputRequest;
import com.probeflow.testagent.controlledplanner.PlannerTaskState;
import com.probeflow.testagent.controlledplanner.ProposedPlanStep;
import com.probeflow.testagent.controlledplanner.RequiredHumanInput;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyValidatorHumanConfirmationGateTests {

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final PolicyValidatorService validator = new PolicyValidatorService(registry, policyService);

    @Test
    void contractAndExecutionModeHumanConfirmationRequiredReturnsHumanConfirmationResult() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.TEST_DESIGN);

        var result = validate(
            insertStep("testcase.review-draft", ToolRiskLevel.LOW, 0.9d),
            input(policy),
            policy,
            Map.of("taskId", "task-1", "draftId", "draft-1", "decision", "APPROVE"),
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.TEST_CASE_DRAFT_EXISTS)
        );

        assertThat(result.requiresHumanConfirmation()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.HUMAN_CONFIRMATION_REQUIRED);
        assertThat(result.message()).contains("requires human confirmation");
        assertThat(result.blockers()).containsExactly("HUMAN_CONFIRMATION_REQUIRED");
    }

    @Test
    void reviewRequiredWorkflowTurnsNonReadOnlyAllowedToolIntoHumanConfirmation() {
        var policy = AgentPolicy.v2Phase2Default()
            .withTaskPhase(AgentTaskPhase.TEST_DESIGN)
            .withWorkflowMode(AgentWorkflowMode.REVIEW_REQUIRED);

        var result = validate(
            insertStep("testcase.generate-drafts", ToolRiskLevel.LOW, 0.9d),
            input(policy),
            policy,
            Map.of("taskId", "task-1", "apiSpecId", "api-1", "generationMode", "SINGLE"),
            Set.of(ToolPrecondition.TASK_EXISTS, ToolPrecondition.API_SPEC_AVAILABLE, ToolPrecondition.CONTEXT_BUNDLE_AVAILABLE)
        );

        assertThat(result.requiresHumanConfirmation()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.REVIEW_REQUIRED_WORKFLOW);
        assertThat(result.blockers()).containsExactly("REVIEW_REQUIRED_WORKFLOW");
    }

    @Test
    void highRiskPlannerDecisionCannotBeSilentlyAllowedEvenWhenToolPolicyAllowsIt() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING);

        var result = validate(
            insertStep("knowledge.retrieve-context", ToolRiskLevel.HIGH, 0.92d),
            input(policy),
            policy,
            knowledgeInput(),
            Set.of(ToolPrecondition.TASK_EXISTS)
        );

        assertThat(result.requiresHumanConfirmation()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.HIGH_RISK_REQUIRES_CONFIRMATION);
        assertThat(result.blockers()).containsExactly("HIGH_RISK_DECISION");
    }

    @Test
    void lowConfidencePlannerDecisionCannotBeSilentlyAllowedEvenWhenToolPolicyAllowsIt() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING);

        var result = validate(
            insertStep("knowledge.retrieve-context", ToolRiskLevel.LOW, 0.31d),
            input(policy),
            policy,
            knowledgeInput(),
            Set.of(ToolPrecondition.TASK_EXISTS)
        );

        assertThat(result.requiresHumanConfirmation()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.LOW_CONFIDENCE_REQUIRES_CONFIRMATION);
        assertThat(result.blockers()).containsExactly("LOW_CONFIDENCE_DECISION");
    }

    @Test
    void waitForHumanInputIsCarriedIntoValidationResultForFutureInteractionLayer() {
        var humanInput = RequiredHumanInput.blocking(
            "Credential policy is unclear",
            "May the agent use staging credentials?",
            List.of(HumanInputField.required("approved", "boolean", "Whether staging credentials may be used."))
        );

        var result = validate(
            PlanDecision.waitForHuman("Need credential approval", 0.4d, ToolRiskLevel.HIGH, humanInput),
            input(AgentPolicy.v2Phase2Default()),
            AgentPolicy.v2Phase2Default(),
            Map.of(),
            Set.of()
        );

        assertThat(result.requiresHumanConfirmation()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.HUMAN_INPUT_REQUIRED);
        assertThat(result.requiredHumanInput()).isEqualTo(humanInput);
        assertThat(result.auditSummary()).containsEntry("requiresHumanInput", true);
    }

    private PolicyValidationResult validate(
        PlanDecision decision,
        PlannerInput input,
        AgentPolicy policy,
        Map<String, Object> toolInput,
        Set<ToolPrecondition> preconditions
    ) {
        return validator.validate(new PolicyValidationRequest(decision, input, policy, toolInput, preconditions));
    }

    private PlanDecision insertStep(String toolName, ToolRiskLevel riskLevel, double confidence) {
        return PlanDecision.insertStep(
            "Need a tool-backed step.",
            confidence,
            riskLevel,
            toolName,
            ProposedPlanStep.of("USE_TOOL", "Use " + toolName, "Planner recommends the registered tool.", toolName)
        );
    }

    private PlannerInput input(AgentPolicy policy) {
        return new PlannerInputFactory(new PlannerSafeToolCatalogService(registry, policyService)).build(
            new PlannerInputRequest(
                PlannerTaskState.of("task-human-gate", "API_TEST", "PLANNING", "step-1", List.of("USE_TOOL")),
                policy,
                null,
                ContextBundleSummary.of("Human confirmation gate context", List.of("wiki/human-gate"), 1, 0, 200),
                List.of()
            )
        );
    }

    private Map<String, Object> knowledgeInput() {
        return Map.of("taskId", "task-1", "query", "auth boundary");
    }
}
