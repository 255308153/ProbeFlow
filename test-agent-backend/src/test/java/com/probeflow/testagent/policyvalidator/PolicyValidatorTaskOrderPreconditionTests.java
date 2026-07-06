package com.probeflow.testagent.policyvalidator;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.controlledplanner.ContextBundleSummary;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerInput;
import com.probeflow.testagent.controlledplanner.PlannerInputFactory;
import com.probeflow.testagent.controlledplanner.PlannerInputRequest;
import com.probeflow.testagent.controlledplanner.PlannerTaskState;
import com.probeflow.testagent.controlledplanner.ProposedPlanStep;
import com.probeflow.testagent.orchestration.StepOutcome;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyValidatorTaskOrderPreconditionTests {

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final PolicyValidatorService validator = new PolicyValidatorService(registry, policyService);

    @Test
    void apiSpecDependentStepIsBlockedWhenPlannerInputHasNoApiSpecSignal() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.TEST_DESIGN);

        var result = validate(
            insertStep("testcase.generate-drafts", ToolRiskLevel.MEDIUM),
            input(policy, context(), StepOutcome.succeeded("Context loaded", List.of("context-1"))),
            policy,
            Map.of("taskId", "task-1", "generationMode", "SINGLE")
        );

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.MISSING_API_SPEC);
        assertThat(result.blockers()).containsExactly("MISSING_API_SPEC");
    }

    @Test
    void testDesignIsBlockedWhenPlannerInputHasApiSpecButNoContextBundle() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.TEST_DESIGN);

        var result = validate(
            insertStep("testcase.generate-drafts", ToolRiskLevel.MEDIUM),
            input(policy, ContextBundleSummary.empty(), StepOutcome.succeeded("ApiSpec created", List.of("apiSpec:api-1"))),
            policy,
            Map.of("taskId", "task-1", "apiSpecId", "api-1", "generationMode", "SINGLE")
        );

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.MISSING_CONTEXT_BUNDLE);
        assertThat(result.blockers()).containsExactly("MISSING_CONTEXT_BUNDLE");
    }

    @Test
    void httpExecutionIsBlockedBeforeApprovedReviewedCaseReadinessExists() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.EXECUTION);

        var result = validate(
            insertStep("http.execute-approved-case", ToolRiskLevel.LOW),
            input(policy, context(), StepOutcome.succeeded("Drafts generated", List.of("draft-1"))),
            policy,
            Map.of("taskId", "task-1", "testCaseId", "case-1", "targetEnvironment", "local")
        );

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.MISSING_TEST_CASE);
        assertThat(result.blockers()).containsExactly("MISSING_TEST_CASE");
    }

    @Test
    void failureAnalysisIsBlockedWhenExecutionRecordHasNoFailureSignal() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.FAILURE_ANALYSIS);

        var result = validate(
            insertStep("failure.analyze-execution", ToolRiskLevel.MEDIUM),
            input(policy, context(), StepOutcome.succeeded("Execution passed", List.of("exec-1"))),
            policy,
            Map.of("taskId", "task-1", "executionRecordId", "exec-1")
        );

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.MISSING_FAILURE_SIGNAL);
        assertThat(result.blockers()).containsExactly("MISSING_FAILURE_SIGNAL");
    }

    @Test
    void reportGenerationIsBlockedWhenTaskProcessDataIsInsufficient() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.REPORTING);

        var result = validate(
            insertStep("report.generate-task", ToolRiskLevel.LOW),
            input(policy, ContextBundleSummary.empty(), null),
            policy,
            Map.of("taskId", "task-1")
        );

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.INSUFFICIENT_REPORT_DATA);
        assertThat(result.blockers()).containsExactly("INSUFFICIENT_REPORT_DATA");
    }

    @Test
    void capabilityGroupPhaseMismatchStillReturnsAgentPolicyPhaseReasonCode() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.TEST_DESIGN);

        var result = validate(
            insertStep("knowledge.retrieve-context", ToolRiskLevel.LOW),
            input(policy, context(), StepOutcome.succeeded("ApiSpec created", List.of("apiSpec:api-1"))),
            policy,
            Map.of("taskId", "task-1", "query", "auth boundary")
        );

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.TOOL_NOT_ALLOWED_IN_TASK_PHASE);
        assertThat(result.blockers()).containsExactly("TOOL_NOT_ALLOWED_IN_TASK_PHASE");
    }

    @Test
    void legalApiSpecAndContextSequenceCanProceedToToolPolicyEvaluation() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.TEST_DESIGN);

        var result = validate(
            insertStep("testcase.generate-drafts", ToolRiskLevel.MEDIUM),
            input(policy, context(), StepOutcome.succeeded("ApiSpec created", List.of("apiSpec:api-1"))),
            policy,
            Map.of("taskId", "task-1", "apiSpecId", "api-1", "generationMode", "SINGLE")
        );

        assertThat(result.allowed()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.TOOL_ALLOWED_BY_POLICY);
    }

    private PolicyValidationResult validate(
        PlanDecision decision,
        PlannerInput input,
        AgentPolicy policy,
        Map<String, Object> toolInput
    ) {
        return validator.validate(new PolicyValidationRequest(decision, input, policy, toolInput, Set.of()));
    }

    private PlanDecision insertStep(String toolName, ToolRiskLevel riskLevel) {
        return PlanDecision.insertStep(
            "Need a phase-ordered tool-backed step.",
            0.86d,
            riskLevel,
            toolName,
            ProposedPlanStep.of("USE_TOOL", "Use " + toolName, "Planner recommends the registered tool.", toolName)
        );
    }

    private PlannerInput input(AgentPolicy policy, ContextBundleSummary context, StepOutcome outcome) {
        return new PlannerInputFactory(new PlannerSafeToolCatalogService(registry, policyService)).build(
            new PlannerInputRequest(
                PlannerTaskState.of("task-phase-order", "API_TEST", "PLANNING", "step-1", List.of("USE_TOOL")),
                policy,
                outcome,
                context,
                List.of()
            )
        );
    }

    private ContextBundleSummary context() {
        return ContextBundleSummary.of("Auth and validation context", List.of("wiki/auth"), 1, 1, 200);
    }
}
