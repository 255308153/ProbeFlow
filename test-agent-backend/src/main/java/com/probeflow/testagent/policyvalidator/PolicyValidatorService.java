package com.probeflow.testagent.policyvalidator;

import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import com.probeflow.testagent.agentpolicy.ToolName;
import com.probeflow.testagent.agentpolicy.ToolPolicyDecision;
import com.probeflow.testagent.agentpolicy.ToolPolicyEvaluationRequest;
import com.probeflow.testagent.agentpolicy.ToolPolicyReasonCode;
import com.probeflow.testagent.agentpolicy.ToolPolicyStatus;
import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerAction;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PolicyValidatorService {

    private static final double MINIMUM_AUTO_CONFIDENCE = 0.5d;

    private final ToolContractRegistry registry;
    private final AgentPolicyService policyService;

    public PolicyValidatorService(ToolContractRegistry registry, AgentPolicyService policyService) {
        this.registry = registry;
        this.policyService = policyService;
    }

    public PolicyValidationResult validate(PolicyValidationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("policy validation request is required");
        }
        var decision = request.decision();
        if (decision.blocked()) {
            return PolicyValidationResult.blocked(
                decision,
                PolicyValidationReasonCode.PLANNER_BLOCKED,
                "Planner returned a blocked decision that cannot enter execution.",
                decision.blockers()
            );
        }
        if (decision.failed()) {
            return PolicyValidationResult.blocked(
                decision,
                PolicyValidationReasonCode.PLANNER_FAILED,
                "Planner failed to produce a safe executable decision.",
                decision.blockers()
            );
        }
        return switch (decision.action()) {
            case CONTINUE -> validateContinue(decision);
            case INSERT_STEP -> validateInsertStep(request);
            case REPLAN -> allowedAfterDecisionGate(
                decision,
                PolicyValidationReasonCode.SAFE_REPLAN,
                "Planner requested replanning as a safe signal without executing a tool."
            );
            case WAIT_FOR_HUMAN -> validateWaitForHuman(decision);
            case STOP -> validateStop(decision);
        };
    }

    private PolicyValidationResult validateContinue(PlanDecision decision) {
        if (decision.proposedToolName() != null) {
            return PolicyValidationResult.blocked(
                decision,
                PolicyValidationReasonCode.INVALID_REQUEST,
                "CONTINUE cannot carry a new tool invocation.",
                List.of("CONTINUE_WITH_TOOL_INTENT")
            );
        }
        return allowedAfterDecisionGate(
            decision,
            PolicyValidationReasonCode.SAFE_CONTINUE,
            "Planner decision can continue the deterministic flow without a tool invocation."
        );
    }

    private PolicyValidationResult validateInsertStep(PolicyValidationRequest request) {
        var decision = request.decision();
        if (decision.proposedPlanStep() == null) {
            return PolicyValidationResult.blocked(
                decision,
                PolicyValidationReasonCode.MISSING_PROPOSED_STEP,
                "INSERT_STEP requires a proposed plan step.",
                List.of("MISSING_PROPOSED_STEP")
            );
        }
        var proposedToolName = proposedToolName(decision);
        if (proposedToolName != null) {
            var nameMismatch = decision.proposedToolName() != null
                && decision.proposedPlanStep().proposedToolName() != null
                && !decision.proposedToolName().equals(decision.proposedPlanStep().proposedToolName());
            if (nameMismatch) {
                return PolicyValidationResult.blocked(
                    decision,
                    PolicyValidationReasonCode.INVALID_REQUEST,
                    "Planner proposed conflicting tool names for INSERT_STEP.",
                    List.of("PROPOSED_TOOL_NAME_MISMATCH")
                );
            }
            return validateProposedTool(decision, proposedToolName, request);
        }
        return allowedAfterDecisionGate(
            decision,
            PolicyValidationReasonCode.SAFE_INSERT_STEP,
            "Planner proposed a structured step; tool policy validation can inspect it before execution."
        );
    }

    private PolicyValidationResult validateWaitForHuman(PlanDecision decision) {
        if (decision.requiredHumanInput() == null) {
            return PolicyValidationResult.blocked(
                decision,
                PolicyValidationReasonCode.MISSING_HUMAN_INPUT,
                "WAIT_FOR_HUMAN requires actionable human input details.",
                List.of("MISSING_HUMAN_INPUT")
            );
        }
        return PolicyValidationResult.requiresHumanConfirmation(
            decision,
            PolicyValidationReasonCode.HUMAN_INPUT_REQUIRED,
            "Planner requires human input before continuing.",
            List.of()
        );
    }

    private PolicyValidationResult validateStop(PlanDecision decision) {
        if (decision.proposedToolName() != null || decision.proposedPlanStep() != null) {
            return PolicyValidationResult.blocked(
                decision,
                PolicyValidationReasonCode.STOP_WITH_TOOL_INTENT,
                "STOP cannot carry a tool invocation or proposed plan step.",
                List.of("STOP_WITH_TOOL_INTENT")
            );
        }
        return allowedAfterDecisionGate(
            decision,
            PolicyValidationReasonCode.SAFE_STOP,
            "Planner requested a safe task stop without executing a tool."
        );
    }

    private PolicyValidationResult validateProposedTool(
        PlanDecision decision,
        String rawToolName,
        PolicyValidationRequest request
    ) {
        ToolName toolName;
        try {
            toolName = ToolName.of(rawToolName);
        } catch (IllegalArgumentException exception) {
            return PolicyValidationResult.blocked(
                decision,
                PolicyValidationReasonCode.UNKNOWN_TOOL,
                "Planner proposed an unknown or invalid tool name: " + rawToolName,
                List.of("UNKNOWN_TOOL")
            );
        }
        if (registry.find(toolName).isEmpty()) {
            return PolicyValidationResult.blocked(
                decision,
                PolicyValidationReasonCode.UNKNOWN_TOOL,
                "Planner proposed an unregistered tool: " + toolName,
                List.of("UNKNOWN_TOOL")
            );
        }
        if (request == null || !visibleToPlanner(toolName, request)) {
            return PolicyValidationResult.blocked(
                decision,
                PolicyValidationReasonCode.TOOL_NOT_VISIBLE,
                "Planner proposed a tool that is not visible in the current planner input: " + toolName,
                List.of("TOOL_NOT_VISIBLE")
            );
        }
        return fromToolPolicyDecision(decision, policyService.evaluate(ToolPolicyEvaluationRequest.of(
            toolName,
            request.policy(),
            request.proposedToolInput(),
            request.satisfiedPreconditions()
        )));
    }

    private PolicyValidationResult fromToolPolicyDecision(PlanDecision decision, ToolPolicyDecision toolDecision) {
        var reasonCode = mapReasonCode(toolDecision.reasonCode());
        if (toolDecision.status() == ToolPolicyStatus.ALLOWED) {
            return allowedAfterDecisionGate(decision, reasonCode, toolDecision.message());
        }
        if (toolDecision.status() == ToolPolicyStatus.REQUIRES_HUMAN_CONFIRMATION) {
            return PolicyValidationResult.requiresHumanConfirmation(
                decision,
                reasonCode,
                toolDecision.message(),
                List.of(toolDecision.reasonCode().name())
            );
        }
        return PolicyValidationResult.blocked(
            decision,
            reasonCode,
            toolDecision.message(),
            List.of(toolDecision.reasonCode().name())
        );
    }

    private PolicyValidationReasonCode mapReasonCode(ToolPolicyReasonCode reasonCode) {
        return switch (reasonCode) {
            case ALLOWED_BY_POLICY -> PolicyValidationReasonCode.TOOL_ALLOWED_BY_POLICY;
            case HUMAN_CONFIRMATION_REQUIRED -> PolicyValidationReasonCode.HUMAN_CONFIRMATION_REQUIRED;
            case REVIEW_REQUIRED_WORKFLOW -> PolicyValidationReasonCode.REVIEW_REQUIRED_WORKFLOW;
            case UNKNOWN_TOOL -> PolicyValidationReasonCode.UNKNOWN_TOOL;
            case TOOL_NOT_WHITELISTED -> PolicyValidationReasonCode.TOOL_NOT_WHITELISTED;
            case TOOL_BLOCKED_BY_CONTRACT -> PolicyValidationReasonCode.TOOL_BLOCKED_BY_CONTRACT;
            case TOOL_NOT_ALLOWED_IN_TASK_PHASE -> PolicyValidationReasonCode.TOOL_NOT_ALLOWED_IN_TASK_PHASE;
            case MISSING_REQUIRED_INPUT -> PolicyValidationReasonCode.MISSING_REQUIRED_INPUT;
            case INVALID_INPUT_TYPE -> PolicyValidationReasonCode.INVALID_INPUT_TYPE;
            case INVALID_INPUT_VALUE -> PolicyValidationReasonCode.INVALID_INPUT_VALUE;
            case MISSING_PRECONDITION -> PolicyValidationReasonCode.MISSING_PRECONDITION;
            case V1_BOUNDARY_BLOCKED -> PolicyValidationReasonCode.V1_BOUNDARY_BLOCKED;
        };
    }

    private boolean visibleToPlanner(ToolName toolName, PolicyValidationRequest request) {
        return request.plannerInput().availableTools().stream()
            .anyMatch(tool -> tool.name().equals(toolName.value()));
    }

    private String proposedToolName(PlanDecision decision) {
        if (decision.proposedToolName() != null) {
            return decision.proposedToolName();
        }
        return decision.proposedPlanStep() == null ? null : decision.proposedPlanStep().proposedToolName();
    }

    private PolicyValidationResult allowedAfterDecisionGate(
        PlanDecision decision,
        PolicyValidationReasonCode reasonCode,
        String message
    ) {
        var gate = decisionGate(decision);
        if (gate != null) {
            return gate;
        }
        return PolicyValidationResult.allowed(decision, reasonCode, message);
    }

    private PolicyValidationResult decisionGate(PlanDecision decision) {
        if (decision.riskLevel() == ToolRiskLevel.HIGH || decision.riskLevel() == ToolRiskLevel.CRITICAL) {
            return PolicyValidationResult.requiresHumanConfirmation(
                decision,
                PolicyValidationReasonCode.HIGH_RISK_REQUIRES_CONFIRMATION,
                "Planner decision is high risk and requires human confirmation before it can proceed.",
                List.of("HIGH_RISK_DECISION")
            );
        }
        if (decision.confidence() < MINIMUM_AUTO_CONFIDENCE) {
            return PolicyValidationResult.requiresHumanConfirmation(
                decision,
                PolicyValidationReasonCode.LOW_CONFIDENCE_REQUIRES_CONFIRMATION,
                "Planner confidence is below the automatic execution threshold.",
                List.of("LOW_CONFIDENCE_DECISION")
            );
        }
        return null;
    }
}
