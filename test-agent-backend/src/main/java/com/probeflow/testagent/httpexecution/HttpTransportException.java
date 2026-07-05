package com.probeflow.testagent.httpexecution;

public class HttpTransportException extends RuntimeException {

    private final String errorType;
    private final long durationMs;

    private HttpTransportException(String errorType, String message, long durationMs) {
        super(message);
        this.errorType = errorType;
        this.durationMs = Math.max(0L, durationMs);
    }

    public static HttpTransportException networkError(String message, long durationMs) {
        return new HttpTransportException("NETWORK_ERROR", message, durationMs);
    }

    public static HttpTransportException timeout(String message, long durationMs) {
        return new HttpTransportException("TIMEOUT", message, durationMs);
    }

    public String errorType() {
        return errorType;
    }

    public long durationMs() {
        return durationMs;
    }
}
