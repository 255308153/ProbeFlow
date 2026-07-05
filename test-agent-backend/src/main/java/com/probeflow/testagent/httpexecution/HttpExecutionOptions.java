package com.probeflow.testagent.httpexecution;

import java.util.List;

public record HttpExecutionOptions(
    long timeoutMs,
    boolean continueOnFailure,
    boolean stopOnCriticalFailure,
    HttpRedirectPolicy redirectPolicy,
    int maxRetries,
    List<String> blockedHosts
) {

    public HttpExecutionOptions(long timeoutMs, boolean continueOnFailure, boolean stopOnCriticalFailure) {
        this(timeoutMs, continueOnFailure, stopOnCriticalFailure, HttpRedirectPolicy.NEVER, 0, List.of());
    }

    public HttpExecutionOptions {
        redirectPolicy = redirectPolicy == null ? HttpRedirectPolicy.NEVER : redirectPolicy;
        maxRetries = Math.max(0, maxRetries);
        blockedHosts = blockedHosts == null ? List.of() : List.copyOf(blockedHosts);
    }

    public static HttpExecutionOptions defaults() {
        return new HttpExecutionOptions(30_000L, true, false, HttpRedirectPolicy.NEVER, 0, List.of());
    }
}
