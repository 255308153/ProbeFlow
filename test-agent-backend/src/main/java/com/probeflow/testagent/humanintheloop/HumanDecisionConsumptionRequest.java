package com.probeflow.testagent.humanintheloop;

public record HumanDecisionConsumptionRequest(
    String requestId,
    String consumer
) {

    public HumanDecisionConsumptionRequest {
        requestId = requestId == null ? "" : requestId.trim();
        consumer = consumer == null ? "" : consumer.trim();
    }
}
