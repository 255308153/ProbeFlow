package com.probeflow.testagent.testcasegeneration;

public record ScenarioCoverage(
    ScenarioCategory category,
    CoverageStatus status,
    String reason,
    String draftId
) {
}
