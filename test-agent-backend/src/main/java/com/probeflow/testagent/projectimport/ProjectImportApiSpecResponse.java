package com.probeflow.testagent.projectimport;

import java.util.Map;

public record ProjectImportApiSpecResponse(
    String apiSpecId,
    String httpMethod,
    String path,
    String moduleName,
    String summary,
    Map<String, Object> sourceLocation,
    String sourceMaterialId,
    boolean routeReady,
    boolean basicParamReady,
    boolean dtoExpanded,
    boolean validationReady,
    boolean authReady,
    boolean knowledgeContextReady,
    boolean presentInLatestAnalysis
) {
}
