package com.probeflow.testagent.policyvalidator;

public enum PolicyValidationReasonCode {
    SAFE_CONTINUE,
    SAFE_INSERT_STEP,
    SAFE_REPLAN,
    SAFE_STOP,
    HUMAN_INPUT_REQUIRED,
    MISSING_HUMAN_INPUT,
    MISSING_PROPOSED_STEP,
    STOP_WITH_TOOL_INTENT,
    PLANNER_BLOCKED,
    PLANNER_FAILED,
    INVALID_REQUEST
}
