package com.probeflow.testagent.suitedraft;

import java.util.List;

public record SuiteReadinessValidationResult(
    SuiteReadinessStatus status,
    List<SuiteReadinessDiagnostic> diagnostics,
    List<SuiteReadinessDiagnostic> blockers
) {

    public SuiteReadinessValidationResult {
        status = status == null ? SuiteReadinessStatus.READY : status;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }
}
