package com.probeflow.testagent.controlledplanner;

public interface ControlledPlanner {

    PlanDecision plan(PlannerInput input);
}
