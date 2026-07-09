package com.probeflow.testagent.projectimport;

public record ProjectImportDiagnostic(
    String code,
    String summary,
    String message,
    String suggestedAction
) {
}
