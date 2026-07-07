package com.probeflow.testagent.suitedraft;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public record SuiteVariableDependency(
    String dependencyId,
    String producerStepId,
    String consumerStepId,
    String variableName,
    SuiteDependencySourceType sourceType,
    String sourcePath,
    SuiteConsumerLocation consumerLocation,
    String consumerField,
    SuiteVariableScope targetScope,
    String targetKey,
    String referenceExpression,
    double confidence,
    List<String> evidenceRefs,
    boolean conflict,
    boolean required,
    String riskLevel,
    Map<String, Object> metadata
) {

    public SuiteVariableDependency {
        sourceType = sourceType == null ? SuiteDependencySourceType.BODY_JSON : sourceType;
        consumerLocation = consumerLocation == null ? SuiteConsumerLocation.PATH : consumerLocation;
        targetScope = targetScope == null ? SuiteVariableScope.SUITE : targetScope;
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        riskLevel = riskLevel == null || riskLevel.isBlank() ? "MEDIUM" : riskLevel;
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
