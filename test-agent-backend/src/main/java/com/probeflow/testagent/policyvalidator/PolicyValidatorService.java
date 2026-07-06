package com.probeflow.testagent.policyvalidator;

import com.probeflow.testagent.controlledplanner.PlannerAction;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PolicyValidatorService {

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
            case INSERT_STEP -> validateInsertStep(decision);
            case REPLAN -> PolicyValidationResult.allowed(
                decision,
                PolicyValidationReasonCode.SAFE_REPLAN,
                "Planner requested replanning as a safe signal without executing a tool."
            );
            case WAIT_FOR_HUMAN -> validateWaitForHuman(decision);
            case STOP -> validateStop(decision);
        };
    }

    private PolicyValidationResult validateContinue(com.probeflow.testagent.controlledplanner.PlanDecision decision) {
        if (decision.proposedToolName() != null) {
            return PolicyValidationResult.blocked(
                decision,
                PolicyValidationReasonCode.INVALID_REQUEST,
                "CONTINUE cannot carry a new tool invocation.",
                List.of("CONTINUE_WITH_TOOL_INTENT")
            );
        }
        return PolicyValidationResult.allowed(
            decision,
            PolicyValidationReasonCode.SAFE_CONTINUE,
            "Planner decision can continue the deterministic flow without a tool invocation."
        );
    }

    private PolicyValidationResult validateInsertStep(com.probeflow.testagent.controlledplanner.PlanDecision decision) {
        if (decision.proposedPlanStep() == null) {
            return PolicyValidationResult.blocked(
                decision,
                PolicyValidationReasonCode.MISSING_PROPOSED_STEP,
                "INSERT_STEP requires a proposed plan step.",
                List.of("MISSING_PROPOSED_STEP")
            );
        }
        return PolicyValidationResult.allowed(
            decision,
            PolicyValidationReasonCode.SAFE_INSERT_STEP,
            "Planner proposed a structured step; tool policy validation can inspect it before execution."
        );
    }

    private PolicyValidationResult validateWaitForHuman(com.probeflow.testagent.controlledplanner.PlanDecision decision) {
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

    private PolicyValidationResult validateStop(com.probeflow.testagent.controlledplanner.PlanDecision decision) {
        if (decision.proposedToolName() != null || decision.proposedPlanStep() != null) {
            return PolicyValidationResult.blocked(
                decision,
                PolicyValidationReasonCode.STOP_WITH_TOOL_INTENT,
                "STOP cannot carry a tool invocation or proposed plan step.",
                List.of("STOP_WITH_TOOL_INTENT")
            );
        }
        return PolicyValidationResult.allowed(
            decision,
            PolicyValidationReasonCode.SAFE_STOP,
            "Planner requested a safe task stop without executing a tool."
        );
    }
}
