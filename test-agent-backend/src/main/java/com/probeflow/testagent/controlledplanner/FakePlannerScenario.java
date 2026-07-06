package com.probeflow.testagent.controlledplanner;

public enum FakePlannerScenario {
    CONTINUE,
    INSERT_STEP,
    REPLAN,
    WAIT_FOR_HUMAN,
    STOP,
    FAILURE,
    MALFORMED
}
