package com.probeflow.testagent.demorun;

import java.util.LinkedHashMap;
import java.util.Map;

public record DemoRunDiagnosticView(
    String code,
    String severity,
    String message,
    Map<String, Object> metadata
) {

    public DemoRunDiagnosticView {
        severity = severity == null || severity.isBlank() ? "ERROR" : severity;
        message = message == null ? "" : message;
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
