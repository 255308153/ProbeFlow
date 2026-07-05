package com.probeflow.testagent.httpexecution;

import java.util.Map;

public record HttpClientResponse(
    int statusCode,
    Map<String, Object> headers,
    Object body,
    long durationMs
) {
}
