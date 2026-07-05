package com.probeflow.testagent.httpexecution;

public record HttpExecutionOptions(
    long timeoutMs,
    boolean continueOnFailure,
    boolean stopOnCriticalFailure
) {

    public static HttpExecutionOptions defaults() {
        return new HttpExecutionOptions(30_000L, true, false);
    }
}
