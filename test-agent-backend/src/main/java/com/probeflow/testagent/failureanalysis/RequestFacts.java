package com.probeflow.testagent.failureanalysis;

import java.util.Map;

public record RequestFacts(
    String method,
    String path,
    String url,
    Map<String, Object> headers
) {
}
