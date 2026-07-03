package com.probeflow.testagent.testcasegeneration;

import java.util.List;

public record TestCaseGenerationResult(
    String taskId,
    String sessionId,
    TestCaseGenerationMode generationMode,
    List<String> targetApiSpecIds,
    List<String> createdDraftIds,
    TestCaseGenerationCounts counts
) {
}
