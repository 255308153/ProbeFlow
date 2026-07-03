package com.probeflow.testagent.analysis;

import java.util.List;

public record ApiAnalysisResult(
    String materialId,
    String taskId,
    boolean succeeded,
    String parserRoute,
    String errorCode,
    String errorMessage,
    List<String> apiSpecIds
) {

    static ApiAnalysisResult success(String materialId, String taskId, String parserRoute, List<String> apiSpecIds) {
        return new ApiAnalysisResult(materialId, taskId, true, parserRoute, null, null, List.copyOf(apiSpecIds));
    }

    static ApiAnalysisResult failure(
        String materialId,
        String taskId,
        String parserRoute,
        String errorCode,
        String errorMessage
    ) {
        return new ApiAnalysisResult(materialId, taskId, false, parserRoute, errorCode, errorMessage, List.of());
    }
}
