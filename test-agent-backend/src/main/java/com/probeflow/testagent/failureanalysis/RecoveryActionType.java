package com.probeflow.testagent.failureanalysis;

public enum RecoveryActionType {
    FIX_EXTRACT_RULE,
    PROVIDE_INPUT,
    REORDER_STEPS,
    CHECK_DOWNSTREAM_API,
    REVIEW_ASSERTION,
    RETRY,
    WAIT_FOR_HUMAN,
    NOOP
}
