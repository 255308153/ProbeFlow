package com.probeflow.testagent.suitedraft;

import java.util.List;

public record SuiteExtractRule(
    String ruleId,
    String producerStepId,
    SuiteDependencySourceType sourceType,
    String sourcePath,
    SuiteVariableScope targetScope,
    String targetKey,
    boolean required,
    SuiteExtractFailureStrategy failureStrategy,
    Object defaultValue,
    String description,
    double confidence,
    List<String> evidenceRefs,
    List<String> consumerStepIds,
    List<String> sourceDependencyIds
) {

    public SuiteExtractRule {
        sourceType = sourceType == null ? SuiteDependencySourceType.BODY_JSON : sourceType;
        targetScope = targetScope == null ? SuiteVariableScope.SUITE : targetScope;
        failureStrategy = failureStrategy == null ? SuiteExtractFailureStrategy.FAIL_FAST : failureStrategy;
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        consumerStepIds = consumerStepIds == null ? List.of() : List.copyOf(consumerStepIds);
        sourceDependencyIds = sourceDependencyIds == null ? List.of() : List.copyOf(sourceDependencyIds);
    }
}
