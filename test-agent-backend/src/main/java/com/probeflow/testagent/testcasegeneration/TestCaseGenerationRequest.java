package com.probeflow.testagent.testcasegeneration;

import java.util.List;

public record TestCaseGenerationRequest(
    String taskId,
    String sessionId,
    List<String> targetApiSpecIds,
    TestCaseGenerationMode generationMode,
    List<ScenarioCategory> scenarioFilters,
    Integer tokenBudget
) {
}
