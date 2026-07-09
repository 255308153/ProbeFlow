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
}
