package com.probeflow.testagent.controlledplanner;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ControlledPlannerService {

    private final ControlledPlanner planner;

    public ControlledPlannerService(ControlledPlanner planner) {
        this.planner = planner;
    }

    public PlanDecision plan(PlannerInput input) {
        if (input == null) {
            throw new IllegalArgumentException("planner input is required");
        }
        try {
            return planner.plan(input);
        } catch (ControlledPlannerException exception) {
            return PlanDecision.failed(
                "Controlled planner failed before producing a safe structured suggestion.",
                List.of(exception.getMessage())
            );
        }
    }
}
