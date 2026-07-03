package com.probeflow.testagent.testcasegeneration;

import java.util.List;
import java.util.Map;

public record TestCasePromotionResult(
    List<String> promotedCaseIds,
    List<String> skippedDraftIds,
    Map<String, String> diagnostics
) {
}
