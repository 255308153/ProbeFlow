package com.probeflow.testagent.manualsuiteagent;

import com.probeflow.testagent.httpexecution.HttpClientRequest;
import java.util.LinkedHashMap;
import java.util.Map;

class ManualSuiteAgentFakeHttpGateway {

    ManualSuiteAgentFakeHttpResponse execute(String stepId, HttpClientRequest request) {
        return execute(stepId, request, "");
    }

    ManualSuiteAgentFakeHttpResponse execute(String stepId, HttpClientRequest request, String failureScenario) {
        var orderId = orderIdFromPath(request == null ? null : request.path());
        if ("variable-extraction-failure".equals(failureScenario) && "create-order".equals(stepId)) {
            return new ManualSuiteAgentFakeHttpResponse(
                201,
                orderedMap(
                    "status", "CREATED",
                    "data", orderedMap("status", "CREATED")
                ),
                15L,
                "order created without expected orderId field"
            );
        }
        if ("prerequisite-step-failure".equals(failureScenario) && "create-order".equals(stepId)) {
            return new ManualSuiteAgentFakeHttpResponse(
                500,
                orderedMap(
                    "orderId", "ORD-1001",
                    "status", "CREATE_FAILED",
                    "data", orderedMap("orderId", "ORD-1001")
                ),
                17L,
                "create order API returned 500"
            );
        }
        if ("downstream-api-failure".equals(failureScenario) && "pay-order".equals(stepId)) {
            return new ManualSuiteAgentFakeHttpResponse(
                503,
                orderedMap("paymentId", "PAY-9001", "orderId", orderId, "status", "PAYMENT_GATEWAY_UNAVAILABLE"),
                22L,
                "payment service returned 503"
            );
        }
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
