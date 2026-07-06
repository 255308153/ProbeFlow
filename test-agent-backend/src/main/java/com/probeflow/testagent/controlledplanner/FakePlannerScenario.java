package com.probeflow.testagent.controlledplanner;

public enum FakePlannerScenario {
    CONTINUE,
    INSERT_STEP,
    ILLEGAL_INSERT_STEP,
    REPLAN,
    HIGH_RISK_REPLAN,
    WAIT_FOR_HUMAN,
    STOP,
    FAILURE,
    MALFORMED
}
