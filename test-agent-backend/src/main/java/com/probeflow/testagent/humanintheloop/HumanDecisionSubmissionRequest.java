package com.probeflow.testagent.humanintheloop;

import java.util.LinkedHashMap;
import java.util.Map;

public record HumanDecisionSubmissionRequest(
    String requestId,
    HumanDecisionType decisionType,
    String actor,
    String reason,
    Map<String, Object> payload
) {

    public HumanDecisionSubmissionRequest {
        requestId = trimToEmpty(requestId);
        actor = trimToEmpty(actor);
        reason = trimToNull(reason);
        payload = payload == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
