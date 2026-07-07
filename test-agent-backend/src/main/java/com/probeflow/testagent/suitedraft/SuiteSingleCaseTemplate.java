package com.probeflow.testagent.suitedraft;

import java.util.LinkedHashMap;
import java.util.Map;

public record SuiteSingleCaseTemplate(
    String templateId,
    String apiSpecId,
    Map<String, Object> requestTemplate,
    Integer expectedStatus,
    Map<String, Object> assertionHints,
    Map<String, Object> metadata
) {

    public SuiteSingleCaseTemplate {
        requestTemplate = requestTemplate == null ? Map.of() : new LinkedHashMap<>(requestTemplate);
        assertionHints = assertionHints == null ? Map.of() : new LinkedHashMap<>(assertionHints);
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
