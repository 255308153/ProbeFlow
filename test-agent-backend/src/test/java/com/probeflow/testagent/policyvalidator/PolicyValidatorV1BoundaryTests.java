package com.probeflow.testagent.policyvalidator;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import com.probeflow.testagent.agentpolicy.ToolName;
import com.probeflow.testagent.agentpolicy.ToolPrecondition;
import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.controlledplanner.ContextBundleSummary;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerInput;
import com.probeflow.testagent.controlledplanner.PlannerInputFactory;
import com.probeflow.testagent.controlledplanner.PlannerInputRequest;
import com.probeflow.testagent.controlledplanner.PlannerTaskState;
import com.probeflow.testagent.controlledplanner.ProposedPlanStep;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyValidatorV1BoundaryTests {

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);
    private final PolicyValidatorService validator = new PolicyValidatorService(registry, policyService);

    @Test
    void v1BoundaryBlocksUiBrowserServiceDbNotificationsTicketsCiMcpAndPluginTools() {
        assertBoundaryBlocked("ui.run-automation");
        assertBoundaryBlocked("browser.run-playwright");
        assertBoundaryBlocked("service.direct-call");
        assertBoundaryBlocked("db.direct-assertion");
        assertBoundaryBlocked("notify.external-message");
        assertBoundaryBlocked("notification.send-email");
        assertBoundaryBlocked("slack.post-message");
        assertBoundaryBlocked("webhook.send-event");
        assertBoundaryBlocked("ticket.create-github");
        assertBoundaryBlocked("github.create-issue");
        assertBoundaryBlocked("jira.create-ticket");
        assertBoundaryBlocked("ci.run-pipeline");
        assertBoundaryBlocked("mcp.invoke-tool");
        assertBoundaryBlocked("plugin.install-marketplace");
    }

    @Test
    void v1BoundaryTakesPrecedenceOverWhitelistVisibilityAndUnknownToolChecks() {
        var whitelistedForbiddenPolicy = new AgentPolicy(
            Set.of(ToolName.of("ui.run-automation")),
            null,
            AgentTaskPhase.EXECUTION,
            true
        );

        var result = validator.validate(new PolicyValidationRequest(
            insertStep("ui.run-automation"),
            input(whitelistedForbiddenPolicy),
            whitelistedForbiddenPolicy,
            Map.of("taskId", "task-1"),
            Set.of(ToolPrecondition.TASK_EXISTS)
        ));

        assertThat(result.blocked()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.V1_BOUNDARY_BLOCKED);
        assertThat(result.blockers()).containsExactly("V1_BOUNDARY_BLOCKED");
    }

    @Test
    void legalBackendApiTestingToolIsNotMistakenForV1BoundaryViolation() {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.CONTEXT_BUILDING);

        var result = validator.validate(new PolicyValidationRequest(
            insertStep("knowledge.retrieve-context"),
            input(policy),
            policy,
            Map.of("taskId", "task-1", "query", "auth boundary"),
            Set.of(ToolPrecondition.TASK_EXISTS)
        ));

        assertThat(result.allowed()).isTrue();
        assertThat(result.reasonCode()).isEqualTo(PolicyValidationReasonCode.TOOL_ALLOWED_BY_POLICY);
    }

    private void assertBoundaryBlocked(String toolName) {
        var policy = AgentPolicy.v2Phase2Default().withTaskPhase(AgentTaskPhase.ANY);
        var result = validator.validate(new PolicyValidationRequest(
            insertStep(toolName),
            input(policy),
            policy,
            Map.of("taskId", "task-1"),
            Set.of(ToolPrecondition.TASK_EXISTS)
        ));

        assertThat(result.blocked()).as(toolName).isTrue();
        assertThat(result.reasonCode()).as(toolName).isEqualTo(PolicyValidationReasonCode.V1_BOUNDARY_BLOCKED);
        assertThat(result.blockers()).as(toolName).containsExactly("V1_BOUNDARY_BLOCKED");
    }

    private PlanDecision insertStep(String toolName) {
        return PlanDecision.insertStep(
            "Planner proposed a tool.",
            0.82d,
            ToolRiskLevel.LOW,
            toolName,
            ProposedPlanStep.of("USE_TOOL", "Use " + toolName, "Planner recommends a tool.", toolName)
        );
    }

    private PlannerInput input(AgentPolicy policy) {
        return new PlannerInputFactory(new PlannerSafeToolCatalogService(registry, policyService)).build(
            new PlannerInputRequest(
                PlannerTaskState.of("task-v1-boundary", "API_TEST", "PLANNING", "step-1", List.of("USE_TOOL")),
                policy,
                null,
                ContextBundleSummary.of("V1 boundary context", List.of("wiki/v1-boundary"), 1, 0, 200),
                List.of()
            )
        );
    }
}
