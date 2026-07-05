package com.probeflow.testagent.httpexecution;

import java.util.Map;

public record ExecutableRequestBuildResult(
    boolean blocked,
    String message,
    HttpClientRequest clientRequest,
    Map<String, Object> requestSnapshot
) {

    public static ExecutableRequestBuildResult blocked(String message, Map<String, Object> requestSnapshot) {
        return new ExecutableRequestBuildResult(true, message, null, requestSnapshot);
    }

    public static ExecutableRequestBuildResult ready(HttpClientRequest clientRequest, Map<String, Object> requestSnapshot) {
        return new ExecutableRequestBuildResult(false, null, clientRequest, requestSnapshot);
    }
}
