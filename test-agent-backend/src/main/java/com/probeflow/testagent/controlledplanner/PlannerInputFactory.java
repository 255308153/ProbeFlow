package com.probeflow.testagent.controlledplanner;

import com.probeflow.testagent.agentpolicy.PlannerSafeToolCatalogService;
import org.springframework.stereotype.Service;

@Service
public class PlannerInputFactory {

    private final PlannerSafeToolCatalogService toolCatalog;

    public PlannerInputFactory(PlannerSafeToolCatalogService toolCatalog) {
        this.toolCatalog = toolCatalog;
    }

    public PlannerInput build(PlannerInputRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("planner input request is required");
        }
        var policy = request.policy();
        return PlannerInput.of(
            request.taskState(),
            policy.taskPhase(),
            policy.workflowMode(),
            LastStepOutcomeSnapshot.from(request.lastStepOutcome()),
            request.contextSummary(),
            toolCatalog.listForPlanner(policy),
            request.constraints()
        );
    }
}
