package com.probeflow.testagent.task;

public enum TaskStatus {
    PENDING,
    ANALYZING,
    CASE_GENERATED,
    WAITING_FOR_REVIEW,
    EXECUTING,
    ANALYZING_RESULTS,
    COMPLETED,
    FAILED,
    CANCELLED
}
