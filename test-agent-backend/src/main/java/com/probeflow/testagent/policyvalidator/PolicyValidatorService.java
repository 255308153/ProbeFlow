package com.probeflow.testagent.policyvalidator;

import com.probeflow.testagent.controlledplanner.PlannerAction;
import org.springframework.stereotype.Service;

@Service
public class PolicyValidatorService {

    public PolicyValidationResult validate(PolicyValidationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("policy validation request is required");
        }
        var decision = request.decision();
        if (decision.action() == PlannerAction.CONTINUE && decision.proposedToolName() == null) {
            return PolicyValidationResult.allowed(
                decision,
                PolicyValidationReasonCode.SAFE_CONTINUE,
                "Planner decision can continue the deterministic flow without a tool invocation."
            );
        }
        return PolicyValidationResult.blocked(
            decision,
            PolicyValidationReasonCode.INVALID_REQUEST,
            "Policy validator only allows no-tool CONTINUE decisions in the initial contract.",
            java.util.List.of("UNSUPPORTED_INITIAL_DECISION")
        );
    }
}
