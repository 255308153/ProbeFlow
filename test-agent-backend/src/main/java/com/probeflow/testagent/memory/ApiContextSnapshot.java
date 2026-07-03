package com.probeflow.testagent.memory;

import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import java.util.Map;

public record ApiContextSnapshot(
    String apiSpecId,
    String systemName,
    String moduleName,
    HttpMethod httpMethod,
    String path,
    String summary,
    String description,
    String operationId,
    Map<String, Object> parameters,
    Map<String, Object> constraints,
    Map<String, Object> auth,
    ApiSpecSourceType sourceType,
    String sourceRef,
    boolean knowledgeContextReady
) {
}
