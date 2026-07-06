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
    private static final Set<String> FORBIDDEN_V1_BOUNDARY_PREFIXES = Set.of(
        "ui.",
        "browser.",
        "service.",
        "db.",
        "notify.",
        "notification.",
        "ticket.",
        "github.",
        "jira.",
        "slack.",
        "webhook.",
        "ci.",
        "mcp.",
        "plugin."
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
        var boundaryDecision = v1BoundaryDecision(toolName, effectivePolicy);
        if (boundaryDecision != null) {
            return boundaryDecision;
        }
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

    public ToolPolicyDecision evaluate(ToolPolicyEvaluationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("tool policy evaluation request is required");
        }
        var policyDecision = evaluateContractPolicy(request.toolName(), request.policy());
        if (!policyDecision.allowed()) {
            return policyDecision;
        }
        var contract = registry.find(request.toolName()).orElseThrow();
        var inputDecision = validateInput(contract, request.input());
        if (inputDecision != null) {
            return inputDecision;
        }
        var preconditionDecision = validatePreconditions(contract, request.satisfiedPreconditions());
        if (preconditionDecision != null) {
            return preconditionDecision;
        }
        return evaluate(request.toolName(), request.policy());
    }

    private ToolPolicyDecision evaluateContractPolicy(ToolName toolName, AgentPolicy policy) {
        var effectivePolicy = policy == null ? AgentPolicy.v2Phase2Default() : policy;
        var boundaryDecision = v1BoundaryDecision(toolName, effectivePolicy);
        if (boundaryDecision != null) {
            return boundaryDecision;
        }
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
        return ToolPolicyDecision.allowed(toolName, "Tool contract is policy-visible: " + toolName);
    }

    private ToolPolicyDecision validateInput(ToolContract contract, Map<String, Object> input) {
        for (var field : contract.inputSchema().fields()) {
            var value = input.get(field.name());
            if (field.required() && missing(value)) {
                return ToolPolicyDecision.blocked(
                    contract.name(),
                    ToolPolicyReasonCode.MISSING_REQUIRED_INPUT,
                    "Missing required input '" + field.name() + "' for tool " + contract.name()
                );
            }
            if (!missing(value) && !typeMatches(value, field.type())) {
                return ToolPolicyDecision.blocked(
                    contract.name(),
                    ToolPolicyReasonCode.INVALID_INPUT_TYPE,
                    "Invalid input type for '" + field.name() + "': expected " + field.type()
                );
            }
            if (!missing(value) && !field.allowedValues().isEmpty() && !field.allowedValues().contains(String.valueOf(value))) {
                return ToolPolicyDecision.blocked(
                    contract.name(),
                    ToolPolicyReasonCode.INVALID_INPUT_VALUE,
                    "Invalid input value for '" + field.name() + "': " + value
                );
            }
        }
        return null;
    }

    private ToolPolicyDecision validatePreconditions(ToolContract contract, Set<ToolPrecondition> satisfiedPreconditions) {
        for (var precondition : contract.preconditions()) {
            if (!satisfiedPreconditions.contains(precondition)) {
                return ToolPolicyDecision.blocked(
                    contract.name(),
                    ToolPolicyReasonCode.MISSING_PRECONDITION,
                    "Missing precondition " + precondition + " for tool " + contract.name()
                );
            }
        }
        return null;
    }

    private boolean missing(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private boolean typeMatches(Object value, ToolSchemaType type) {
        return switch (type) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Integer || value instanceof Long;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case OBJECT -> value instanceof Map<?, ?>;
            case ARRAY -> value instanceof Iterable<?> || value.getClass().isArray();
        };
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

    private ToolPolicyDecision v1BoundaryDecision(ToolName toolName, AgentPolicy policy) {
        if (toolName == null || policy == null || !policy.enforceV1BoundaryRules()) {
            return null;
        }
        var value = toolName.value();
        var blocked = FORBIDDEN_V1_BOUNDARY_PREFIXES.stream().anyMatch(value::startsWith);
        if (!blocked) {
            return null;
        }
        return ToolPolicyDecision.blocked(
            toolName,
            ToolPolicyReasonCode.V1_BOUNDARY_BLOCKED,
            "Tool is blocked by V1 API-testing boundary policy: " + toolName
        );
    }
}
