package com.probeflow.testagent.agentpolicy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HighRiskV1BoundaryPolicyTests {

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final AgentPolicy policy = AgentPolicy.v2Phase2Default().withWorkflowMode(AgentWorkflowMode.AUTOMATIC);

    @Test
    void httpExecutionToolIsHighRiskAndRequiresReadinessReviewedCaseAndHumanConfirmation() {
        var contract = registry.find(ToolNames.HTTP_EXECUTE_APPROVED_CASE).orElseThrow();

        assertThat(contract.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(contract.humanConfirmationRequired()).isTrue();
        assertThat(contract.preconditions()).contains(
            ToolPrecondition.TEST_CASE_EXISTS,
            ToolPrecondition.TEST_CASE_DRAFT_REVIEWED,
            ToolPrecondition.EXECUTION_READINESS_CONFIRMED
        );
    }

    @Test
    void httpExecutionBlocksWhenReadinessIsMissing() {
        var decision = evaluateHttp(Set.of(
            ToolPrecondition.TASK_EXISTS,
            ToolPrecondition.TEST_CASE_EXISTS,
            ToolPrecondition.TEST_CASE_DRAFT_REVIEWED
        ));

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.MISSING_PRECONDITION);
        assertThat(decision.message()).contains("EXECUTION_READINESS_CONFIRMED");
    }

    @Test
    void httpExecutionBlocksWhenApprovedTestCaseOrReviewGateIsMissing() {
        var missingCase = evaluateHttp(Set.of(
            ToolPrecondition.TASK_EXISTS,
            ToolPrecondition.TEST_CASE_DRAFT_REVIEWED,
            ToolPrecondition.EXECUTION_READINESS_CONFIRMED
        ));
        var missingReviewGate = evaluateHttp(Set.of(
            ToolPrecondition.TASK_EXISTS,
            ToolPrecondition.TEST_CASE_EXISTS,
            ToolPrecondition.EXECUTION_READINESS_CONFIRMED
        ));

        assertThat(missingCase.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(missingCase.message()).contains("TEST_CASE_EXISTS");
        assertThat(missingReviewGate.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(missingReviewGate.message()).contains("TEST_CASE_DRAFT_REVIEWED");
    }

    @Test
    void httpExecutionRequiresHumanConfirmationWhenAllStructuralGatesPass() {
        var decision = evaluateHttp(Set.of(
            ToolPrecondition.TASK_EXISTS,
            ToolPrecondition.TEST_CASE_EXISTS,
            ToolPrecondition.TEST_CASE_DRAFT_REVIEWED,
            ToolPrecondition.EXECUTION_READINESS_CONFIRMED
        ));

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.REQUIRES_HUMAN_CONFIRMATION);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.HUMAN_CONFIRMATION_REQUIRED);
    }

    @Test
    void v1BoundaryBlocksUiAutomationServiceDirectDbDirectExternalNotificationsTicketsCiMcpAndPlugins() {
        assertBoundaryBlocked("ui.browser-automation");
        assertBoundaryBlocked("browser.run-playwright");
        assertBoundaryBlocked("service.direct-call");
        assertBoundaryBlocked("db.direct-assertion");
        assertBoundaryBlocked("notify.external-message");
        assertBoundaryBlocked("ticket.create-github");
        assertBoundaryBlocked("ticket.create-jira");
        assertBoundaryBlocked("slack.post-message");
        assertBoundaryBlocked("webhook.send-event");
        assertBoundaryBlocked("ci.run-pipeline");
        assertBoundaryBlocked("mcp.invoke-tool");
        assertBoundaryBlocked("plugin.install-marketplace");
    }

    private ToolPolicyDecision evaluateHttp(Set<ToolPrecondition> satisfiedPreconditions) {
        return policyService.evaluate(ToolPolicyEvaluationRequest.of(
            ToolNames.HTTP_EXECUTE_APPROVED_CASE,
            policy,
            Map.of("taskId", "task-1", "testCaseId", "case-1", "targetEnvironment", "local"),
            satisfiedPreconditions
        ));
    }

    private void assertBoundaryBlocked(String toolName) {
        var decision = policyService.evaluate(toolName, policy);

        assertThat(decision.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
        assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.V1_BOUNDARY_BLOCKED);
        assertThat(decision.message()).contains("V1 API-testing boundary");
    }
}
