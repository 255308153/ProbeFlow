package com.probeflow.testagent.replanning;

public enum ReplanningTrigger {
    PLAN_STEP_FAILED,
    EXECUTION_READINESS_MISSING,
    FAILURE_ANALYSIS_HIGH_RISK,
    REVIEW_COMPLETED,
    CONTEXT_MISSING,
    HUMAN_INPUT_REQUIRED
}
