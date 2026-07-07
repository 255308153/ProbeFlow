package com.probeflow.testagent.manualsuiteagent;

import com.probeflow.testagent.httpexecution.HttpClientRequest;
import java.util.LinkedHashMap;
import java.util.Map;

class ManualSuiteAgentFakeHttpGateway {

    ManualSuiteAgentFakeHttpResponse execute(String stepId, HttpClientRequest request) {
        var orderId = orderIdFromPath(request == null ? null : request.path());
        return switch (stepId) {
            case "create-order" -> new ManualSuiteAgentFakeHttpResponse(
                201,
                orderedMap(
                    "orderId", "ORD-1001",
                    "status", "CREATED",
                    "data", orderedMap("orderId", "ORD-1001", "status", "CREATED")
                ),
                15L,
                "order ORD-1001 created"
            );
            case "pay-order" -> new ManualSuiteAgentFakeHttpResponse(
                200,
                orderedMap("paymentId", "PAY-9001", "orderId", orderId, "status", "PAID"),
                18L,
                "payment PAY-9001 accepted"
            );
            case "query-order" -> new ManualSuiteAgentFakeHttpResponse(
                200,
                orderedMap("orderId", orderId, "paymentStatus", "PAID"),
                12L,
                "order ORD-1001 is PAID"
            );
            default -> new ManualSuiteAgentFakeHttpResponse(
                404,
                orderedMap("error", "No fake response for step " + stepId),
                1L,
                "fake response not found"
            );
        };
    }

    private String orderIdFromPath(String path) {
        if (path == null || path.isBlank()) {
            return "ORD-1001";
        }
        var segments = path.split("/");
        for (var index = 0; index < segments.length - 1; index++) {
            if ("orders".equals(segments[index]) && !segments[index + 1].isBlank()) {
                return segments[index + 1];
            }
        }
        return "ORD-1001";
    }

    private Map<String, Object> orderedMap(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i].toString(), keyValues[i + 1]);
        }
        return map;
    }
}
