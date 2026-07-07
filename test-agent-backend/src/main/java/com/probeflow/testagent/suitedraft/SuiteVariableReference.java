package com.probeflow.testagent.suitedraft;

import java.util.List;

public record SuiteVariableReference(
    String consumerStepId,
    SuiteConsumerLocation consumerLocation,
    String consumerField,
    SuiteVariableScope targetScope,
    String targetKey,
    String referenceExpression,
    String sourceDependencyId,
    List<String> evidenceRefs
) {

    public SuiteVariableReference {
        consumerLocation = consumerLocation == null ? SuiteConsumerLocation.PATH : consumerLocation;
        targetScope = targetScope == null ? SuiteVariableScope.SUITE : targetScope;
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
