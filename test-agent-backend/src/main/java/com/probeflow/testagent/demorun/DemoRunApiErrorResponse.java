package com.probeflow.testagent.demorun;

import java.util.LinkedHashMap;
import java.util.Map;

public record DemoRunApiErrorResponse(
    String code,
    String category,
    String field,
    String message,
    Map<String, Object> details
) {

    public DemoRunApiErrorResponse {
        category = category == null || category.isBlank() ? "VALIDATION" : category;
        field = field == null ? "" : field;
        message = message == null ? "" : message;
        details = details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    public static DemoRunApiErrorResponse validation(String code, String field, String message) {
        return new DemoRunApiErrorResponse(code, "VALIDATION", field, message, Map.of());
    }
}
