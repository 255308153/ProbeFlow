package com.probeflow.testagent.suitedraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SuiteDraftStep(
    String stepId,
    String stepName,
    int order,
    String apiSpecId,
    boolean critical,
    Map<String, Object> requestTemplate,
    Integer expectedStatus,
    List<Map<String, Object>> assertionHints,
    List<SuiteExtractRule> extractRules,
    List<SuiteVariableReference> variableReferences,
    List<String> sourceRefs,
    List<String> dependencyRefs,
    SuiteReadinessStatus readinessStatus,
    Map<String, Object> metadata
) {

    public SuiteDraftStep {
        requestTemplate = requestTemplate == null ? Map.of() : new LinkedHashMap<>(requestTemplate);
        assertionHints = assertionHints == null ? List.of() : List.copyOf(assertionHints);
        extractRules = extractRules == null ? List.of() : List.copyOf(extractRules);
        variableReferences = variableReferences == null ? List.of() : List.copyOf(variableReferences);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        dependencyRefs = dependencyRefs == null ? List.of() : List.copyOf(dependencyRefs);
        readinessStatus = readinessStatus == null ? SuiteReadinessStatus.READY : readinessStatus;
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
