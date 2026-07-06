package com.probeflow.testagent.policyvalidator;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerInput;

public record PolicyValidationRequest(
    PlanDecision decision,
    PlannerInput plannerInput,
    AgentPolicy policy
) {

    public PolicyValidationRequest {
        if (decision == null) {
            throw new IllegalArgumentException("planner decision is required");
        }
        if (plannerInput == null) {
            throw new IllegalArgumentException("planner input is required");
        }
        policy = policy == null ? AgentPolicy.v2Phase2Default() : policy;
    }
}
