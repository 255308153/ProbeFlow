package com.probeflow.testagent.projectimport;

import java.time.Instant;
import java.util.List;

public record ProjectImportDetailResponse(
    String materialId,
    String materialType,
    String projectName,
    String originalRef,
    String storagePath,
    String status,
    String taskId,
    int apiSpecCount,
    Instant lastAnalyzedAt,
    List<ProjectImportDiagnostic> warnings,
    List<ProjectImportDiagnostic> blockers
) {
}
