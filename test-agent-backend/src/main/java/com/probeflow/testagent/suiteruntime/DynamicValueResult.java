package com.probeflow.testagent.suiteruntime;

public record DynamicValueResult(
    boolean success,
    Object value,
    String failureCode,
    String message
) {

    public static DynamicValueResult success(Object value) {
        return new DynamicValueResult(true, value, null, null);
    }

    public static DynamicValueResult failure(String failureCode, String message) {
        return new DynamicValueResult(false, null, failureCode, message);
    }
}
