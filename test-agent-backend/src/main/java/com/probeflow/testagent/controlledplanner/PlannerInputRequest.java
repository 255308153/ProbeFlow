package com.probeflow.testagent.controlledplanner;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.orchestration.StepOutcome;
import com.probeflow.testagent.task.PlanStep;
import java.util.List;

public record PlannerInputRequest(
    PlannerTaskState taskState,
    AgentPolicy policy,
    StepOutcome lastStepOutcome,
    PlanStep lastStep,
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

    public PlannerInputRequest(
        PlannerTaskState taskState,
        AgentPolicy policy,
        StepOutcome lastStepOutcome,
        ContextBundleSummary contextSummary,
        List<PlannerConstraint> constraints
    ) {
        this(taskState, policy, lastStepOutcome, null, contextSummary, constraints);
    }
}
