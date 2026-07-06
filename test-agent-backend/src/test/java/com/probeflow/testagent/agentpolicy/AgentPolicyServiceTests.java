package com.probeflow.testagent.agentpolicy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentPolicyServiceTests {

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);

    @Test
    void whitelistedLowRiskReadOnlyToolIsAllowedWithoutLlmNetworkOrApiKey() {
        var decision = policyService.evaluate(
            ToolNames.KNOWLEDGE_RETRIEVE_CONTEXT,
            AgentPolicy.v2Phase2Default().withWorkflowMode(AgentWorkflowMode.AUTOMATIC)
        );

        assertThat(decision)
            .returns(ToolPolicyStatus.ALLOWED, ToolPolicyDecision::status)
            .returns(ToolPolicyReasonCode.ALLOWED_BY_POLICY, ToolPolicyDecision::reasonCode)
            .returns(ToolNames.KNOWLEDGE_RETRIEVE_CONTEXT, ToolPolicyDecision::toolName);
        assertThat(decision.message()).contains("allowed");
    }

    @Test
    void highRiskWhitelistedToolRequiresHumanConfirmation() {
        var decision = policyService.evaluate(
            ToolNames.HTTP_EXECUTE_APPROVED_CASE,
            AgentPolicy.v2Phase2Default().withWorkflowMode(AgentWorkflowMode.AUTOMATIC)
        );

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.REQUIRES_HUMAN_CONFIRMATION);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.HUMAN_CONFIRMATION_REQUIRED);
        assertThat(decision.requiresHumanConfirmation()).isTrue();
        assertThat(decision.message()).contains("human confirmation");
    }

    @Test
    void reviewRequiredWorkflowRequiresConfirmationForMutatingToolsButAllowsReadOnlyContext() {
        var policy = AgentPolicy.v2Phase2Default().withWorkflowMode(AgentWorkflowMode.REVIEW_REQUIRED);

        var mutating = policyService.evaluate(ToolNames.TESTCASE_GENERATE_DRAFTS, policy);
        var readOnly = policyService.evaluate(ToolNames.MEMORY_BUILD_CONTEXT, policy);

        assertThat(mutating.status()).isEqualTo(ToolPolicyStatus.REQUIRES_HUMAN_CONFIRMATION);
        assertThat(mutating.reasonCode()).isEqualTo(ToolPolicyReasonCode.REVIEW_REQUIRED_WORKFLOW);
        assertThat(readOnly.status()).isEqualTo(ToolPolicyStatus.ALLOWED);
    }

    @Test
    void unregisteredToolNameIsBlockedWithStableReasonCode() {
        var decision = policyService.evaluate("ticket.create-github", AgentPolicy.v2Phase2Default());

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.UNKNOWN_TOOL);
        assertThat(decision.message()).contains("Unknown tool");
    }

    @Test
    void toolOutsideWhitelistIsBlockedEvenWhenRegistered() {
        var policy = AgentPolicy.v2Phase2Default()
            .withWhitelistedTools(Set.of(ToolNames.KNOWLEDGE_RETRIEVE_CONTEXT));

        var decision = policyService.evaluate(ToolNames.REPORT_GENERATE_TASK, policy);

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.TOOL_NOT_WHITELISTED);
        assertThat(decision.message()).contains("not whitelisted");
    }

    @Test
    void currentTaskPhaseCanBlockOtherwiseWhitelistedTools() {
        var policy = AgentPolicy.v2Phase2Default()
            .withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING);

        var allowed = policyService.evaluate(ToolNames.KNOWLEDGE_RETRIEVE_CONTEXT, policy);
        var blocked = policyService.evaluate(ToolNames.HTTP_EXECUTE_APPROVED_CASE, policy);

        assertThat(allowed.status()).isEqualTo(ToolPolicyStatus.ALLOWED);
        assertThat(blocked.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(blocked.reasonCode()).isEqualTo(ToolPolicyReasonCode.TOOL_NOT_ALLOWED_IN_TASK_PHASE);
    }

    @Test
    void malformedFreeTextToolNameIsBlockedBeforeRegistryLookup() {
        var decision = policyService.evaluate("call the database directly", AgentPolicy.v2Phase2Default());

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.UNKNOWN_TOOL);
    }
}
