package com.probeflow.testagent.testcasegeneration;

public record TestCaseGenerationCounts(
    int created,
    int updated,
    int skipped,
    int duplicateSuppressed
) {
}
