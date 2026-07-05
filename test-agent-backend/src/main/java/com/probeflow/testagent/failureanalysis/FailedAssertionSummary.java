package com.probeflow.testagent.failureanalysis;

public record FailedAssertionSummary(
    String name,
    String type,
    Object expected,
    Object actual,
    String path,
    boolean critical,
    String message
) {
}
