package com.probeflow.testagent.testcasegeneration;

import java.util.List;
import java.util.Map;

public record TestCaseGenerationResult(
    String taskId,
    String sessionId,
    TestCaseGenerationMode generationMode,
    List<String> targetApiSpecIds,
    List<String> createdDraftIds,
    List<ScenarioCategory> generatedCategories,
    Map<ScenarioCategory, String> skippedCategories,
    Map<ScenarioCategory, String> unsupportedCategories,
    TestCaseGenerationCounts counts
) {
}
