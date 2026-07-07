package com.probeflow.testagent.suitedraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SuiteDependencyHint(
    String hintId,
    String variableName,
    String producerStepId,
    String consumerStepId,
    SuiteDependencySourceType sourceType,
    String sourcePath,
    SuiteConsumerLocation consumerLocation,
    String consumerField,
    SuiteVariableScope targetScope,
    String targetKey,
    boolean required,
    SuiteExtractFailureStrategy failureStrategy,
    Object defaultValue,
    double confidence,
    List<String> evidenceRefs,
    boolean conflict,
    Map<String, Object> metadata
) {

    public SuiteDependencyHint {
        sourceType = sourceType == null ? SuiteDependencySourceType.BODY_JSON : sourceType;
        consumerLocation = consumerLocation == null ? SuiteConsumerLocation.PATH : consumerLocation;
        targetScope = targetScope == null ? SuiteVariableScope.SUITE : targetScope;
        failureStrategy = failureStrategy == null ? SuiteExtractFailureStrategy.FAIL_FAST : failureStrategy;
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
