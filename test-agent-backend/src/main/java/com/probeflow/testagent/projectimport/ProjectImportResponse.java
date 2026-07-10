package com.probeflow.testagent.projectimport;

import java.util.List;

public record ProjectImportResponse(
    String materialId,
    String taskId,
    String status,
    String materialType,
    int apiSpecCount,
    List<ProjectImportDiagnostic> warnings,
    List<ProjectImportDiagnostic> blockers
) {

    static ProjectImportResponse validatedLocalDirectory() {
        return new ProjectImportResponse(
            null,
            null,
            "VALIDATED",
            "SOURCE_DIRECTORY",
            0,
            List.of(),
            List.of()
        );
    }

    static ProjectImportResponse readySourceDirectory(
        String materialId,
        String taskId,
        int apiSpecCount,
        List<ProjectImportDiagnostic> warnings
    ) {
        return new ProjectImportResponse(
            materialId,
            taskId,
            "READY",
            "SOURCE_DIRECTORY",
            apiSpecCount,
            List.copyOf(warnings),
            List.of()
        );
    }

    static ProjectImportResponse failedSourceDirectory(
        String materialId,
        String taskId,
        ProjectImportDiagnostic blocker,
        List<ProjectImportDiagnostic> warnings
    ) {
        return new ProjectImportResponse(
            materialId,
            taskId,
            "FAILED",
            "SOURCE_DIRECTORY",
            0,
            List.copyOf(warnings),
            List.of(blocker)
        );
    }
}
