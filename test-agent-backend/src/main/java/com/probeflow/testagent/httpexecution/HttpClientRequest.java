package com.probeflow.testagent.httpexecution;

import java.util.Map;

public record HttpClientRequest(
    String method,
    String path,
    String url,
    Map<String, Object> headers,
    Map<String, Object> queryParams,
    Object body
) {

    public HttpClientRequest(String method, String path, Map<String, Object> headers, Object body) {
        this(method, path, path, headers, Map.of(), body);
    }
}
