package com.probeflow.testagent.testcasegeneration;

import java.util.List;

public record TargetCoverageSummary(
    String apiSpecId,
    CoverageStatus status,
    List<ScenarioCoverage> scenarios,
    List<String> warnings,
    List<String> diagnostics
) {
}
