package com.probeflow.testagent.controlledplanner;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.orchestration.StepOutcome;
import java.util.List;

public record PlannerInputRequest(
    PlannerTaskState taskState,
    AgentPolicy policy,
    StepOutcome lastStepOutcome,
    ContextBundleSummary contextSummary,
    List<PlannerConstraint> constraints
) {

    public PlannerInputRequest {
        if (taskState == null) {
            throw new IllegalArgumentException("planner task state is required");
        }
        policy = policy == null ? AgentPolicy.v2Phase2Default() : policy;
        contextSummary = contextSummary == null ? ContextBundleSummary.empty() : contextSummary;
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }
}
