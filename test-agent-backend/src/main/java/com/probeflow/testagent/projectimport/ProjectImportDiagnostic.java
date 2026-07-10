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

    static ProjectImportDiagnostic warning(
        String code,
        String summary,
        String message,
        String suggestedAction
    ) {
        return new ProjectImportDiagnostic(code, summary, message, suggestedAction);
    }

    static ProjectImportDiagnostic analysisBlocker(String code, String fallbackMessage) {
        return switch (code) {
            case "NO_APIS_FOUND" -> blocker(
                code,
                "No analyzable source code found",
                "No Java source files were found in the local directory.",
                "Add Java source files to the selected directory, then rerun analysis."
            );
            case "NO_HTTP_APIS_FOUND" -> blocker(
                code,
                "No HTTP interfaces found",
                "Java source files were found, but no Spring HTTP routes were detected.",
                "Add a Spring @RestController with HTTP request mappings, then rerun analysis."
            );
            case "MULTI_MODULE_SOURCE_ROOTS_AMBIGUOUS" -> blocker(
                code,
                "Multiple source modules require selection",
                "Multiple src/main/java source roots were found, so ProbeFlow cannot safely choose one module to analyze.",
                "Import a single module directory or configure a specific source module, then rerun analysis."
            );
            default -> blocker(
                code,
                "Local directory analysis failed",
                fallbackMessage,
                "Check that the directory contains analyzable Spring @RestController source code."
            );
        };
    }
}
