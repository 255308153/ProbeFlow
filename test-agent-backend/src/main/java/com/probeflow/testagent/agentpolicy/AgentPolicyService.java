package com.probeflow.testagent.agentpolicy;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AgentPolicyService {

    private static final Map<AgentTaskPhase, Set<ToolCapabilityGroup>> PHASE_ALLOWED_GROUPS = Map.of(
        AgentTaskPhase.TASK_SETUP, Set.of(ToolCapabilityGroup.API_ANALYSIS),
        AgentTaskPhase.CONTEXT_BUILDING, Set.of(ToolCapabilityGroup.KNOWLEDGE, ToolCapabilityGroup.MEMORY),
        AgentTaskPhase.TEST_DESIGN, Set.of(ToolCapabilityGroup.TEST_CASE),
        AgentTaskPhase.EXECUTION, Set.of(ToolCapabilityGroup.HTTP_EXECUTION),
        AgentTaskPhase.FAILURE_ANALYSIS, Set.of(ToolCapabilityGroup.FAILURE_ANALYSIS),
        AgentTaskPhase.REPORTING, Set.of(ToolCapabilityGroup.REPORTING)
    );

    private final ToolContractRegistry registry;

    public AgentPolicyService(ToolContractRegistry registry) {
        this.registry = registry;
    }

    public ToolPolicyDecision evaluate(String toolName, AgentPolicy policy) {
        ToolName parsedName;
        try {
            parsedName = ToolName.of(toolName);
        } catch (IllegalArgumentException exception) {
            return ToolPolicyDecision.blocked(null, ToolPolicyReasonCode.UNKNOWN_TOOL, "Unknown tool: " + toolName);
        }
        return evaluate(parsedName, policy);
    }

    public ToolPolicyDecision evaluate(ToolName toolName, AgentPolicy policy) {
        var effectivePolicy = policy == null ? AgentPolicy.v2Phase2Default() : policy;
        var contract = registry.find(toolName);
        if (contract.isEmpty()) {
            return ToolPolicyDecision.blocked(toolName, ToolPolicyReasonCode.UNKNOWN_TOOL, "Unknown tool: " + toolName);
        }
        if (!effectivePolicy.isWhitelisted(toolName)) {
            return ToolPolicyDecision.blocked(
                toolName,
                ToolPolicyReasonCode.TOOL_NOT_WHITELISTED,
                "Tool is not whitelisted by current AgentPolicy: " + toolName
            );
        }
        var tool = contract.orElseThrow();
        if (!allowedInPhase(tool, effectivePolicy.taskPhase())) {
            return ToolPolicyDecision.blocked(
                toolName,
                ToolPolicyReasonCode.TOOL_NOT_ALLOWED_IN_TASK_PHASE,
                "Tool " + toolName + " is not allowed in task phase " + effectivePolicy.taskPhase()
            );
        }
        if (tool.executionMode() == ToolExecutionMode.BLOCKED) {
            return ToolPolicyDecision.blocked(
                toolName,
                ToolPolicyReasonCode.TOOL_BLOCKED_BY_CONTRACT,
                "Tool contract blocks execution: " + toolName
            );
        }
        if (requiresHumanConfirmation(tool, effectivePolicy)) {
            var reasonCode = effectivePolicy.workflowMode() == AgentWorkflowMode.REVIEW_REQUIRED && !tool.readOnly()
                ? ToolPolicyReasonCode.REVIEW_REQUIRED_WORKFLOW
                : ToolPolicyReasonCode.HUMAN_CONFIRMATION_REQUIRED;
            return ToolPolicyDecision.requiresHumanConfirmation(
                toolName,
                reasonCode,
                "Tool requires human confirmation before execution: " + toolName
            );
        }
        return ToolPolicyDecision.allowed(toolName, "Tool is allowed by current AgentPolicy: " + toolName);
    }

    private boolean allowedInPhase(ToolContract tool, AgentTaskPhase phase) {
        if (phase == null || phase == AgentTaskPhase.ANY) {
            return true;
        }
        return PHASE_ALLOWED_GROUPS.getOrDefault(phase, Set.of()).contains(tool.capabilityGroup());
    }

    private boolean requiresHumanConfirmation(ToolContract tool, AgentPolicy policy) {
        if (tool.humanConfirmationRequired()) {
            return true;
        }
        if (tool.executionMode() == ToolExecutionMode.HUMAN_CONFIRMATION_REQUIRED) {
            return true;
        }
        if (tool.riskLevel() == ToolRiskLevel.HIGH || tool.riskLevel() == ToolRiskLevel.CRITICAL) {
            return true;
        }
        return policy.workflowMode() == AgentWorkflowMode.REVIEW_REQUIRED && !tool.readOnly();
    }
}
