package com.probeflow.testagent.failureanalysis;

public record SuiteDependencyFailure(
    String producerStepId,
    Integer producerOrder,
    String consumerStepId,
    Integer consumerOrder,
    String expression,
    String scope,
    String path,
    String variableName,
    String failureReason
) {
}
