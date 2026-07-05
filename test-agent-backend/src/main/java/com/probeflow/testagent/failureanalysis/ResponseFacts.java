package com.probeflow.testagent.failureanalysis;

import java.util.Map;

public record ResponseFacts(
    Integer statusCode,
    String failureType,
    String errorType,
    String bodyType,
    Boolean bodyTruncated,
    Long bodySizeBytes,
    Map<String, Object> headers
) {
}
