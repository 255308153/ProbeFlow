package com.probeflow.testagent.httpexecution;

import java.util.Map;

public record HttpClientRequest(
    String method,
    String path,
    Map<String, Object> headers,
    Object body
) {
}
