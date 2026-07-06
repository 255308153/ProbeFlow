package com.probeflow.testagent.policyvalidator;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.ToolPrecondition;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerInput;
import java.util.Map;
import java.util.Set;

public record PolicyValidationRequest(
    PlanDecision decision,
    PlannerInput plannerInput,
    AgentPolicy policy,
    Map<String, Object> proposedToolInput,
    Set<ToolPrecondition> satisfiedPreconditions
) {

    public PolicyValidationRequest {
        if (decision == null) {
            throw new IllegalArgumentException("planner decision is required");
        }
        if (plannerInput == null) {
            throw new IllegalArgumentException("planner input is required");
        }
        policy = policy == null ? AgentPolicy.v2Phase2Default() : policy;
        proposedToolInput = proposedToolInput == null ? Map.of() : Map.copyOf(proposedToolInput);
        satisfiedPreconditions = satisfiedPreconditions == null ? Set.of() : Set.copyOf(satisfiedPreconditions);
    }

    public PolicyValidationRequest(
        PlanDecision decision,
        PlannerInput plannerInput,
        AgentPolicy policy
    ) {
        this(decision, plannerInput, policy, Map.of(), Set.of());
    }
}
