package com.probeflow.testagent.projectimport;

public record ProjectImportDiagnostic(
    String code,
    String summary,
    String message,
    String suggestedAction
) {

    static ProjectImportDiagnostic blocker(
        String code,
        String summary,
        String message,
        String suggestedAction
    ) {
        return new ProjectImportDiagnostic(code, summary, message, suggestedAction);
    }
}
