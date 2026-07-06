package com.probeflow.testagent.replanning;

public enum ReplanningStatus {
    APPLIED,
    NOOP,
    WAITING_FOR_HUMAN,
    REJECTED_BY_POLICY,
    NOT_TRIGGERABLE,
    FAILED
}
