package com.probeflow.testagent.analysis;

import com.probeflow.testagent.sourcematerial.MaterialType;

public record ApiAnalysisRequest(
    String materialId,
    MaterialType materialType,
    String originalName,
    String originalRef,
    String storagePath,
    String requestedBy
) {

    public static ApiAnalysisRequest createMaterial(
        MaterialType materialType,
        String originalName,
        String originalRef,
        String storagePath,
        String requestedBy
    ) {
        return new ApiAnalysisRequest(null, materialType, originalName, originalRef, storagePath, requestedBy);
    }

    public static ApiAnalysisRequest existingMaterial(String materialId, String requestedBy) {
        return new ApiAnalysisRequest(materialId, null, null, null, null, requestedBy);
    }
}
