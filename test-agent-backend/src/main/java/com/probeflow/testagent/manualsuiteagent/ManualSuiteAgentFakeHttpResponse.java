package com.probeflow.testagent.manualsuiteagent;

import java.util.LinkedHashMap;
import java.util.Map;

record ManualSuiteAgentFakeHttpResponse(
    int statusCode,
    Map<String, Object> body,
    long durationMs,
    String summary
) {

    ManualSuiteAgentFakeHttpResponse {
        body = body == null ? Map.of() : new LinkedHashMap<>(body);
    }
}
