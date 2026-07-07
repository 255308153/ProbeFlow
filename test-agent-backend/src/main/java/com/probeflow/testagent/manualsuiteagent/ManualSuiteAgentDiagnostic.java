package com.probeflow.testagent.manualsuiteagent;

import java.util.LinkedHashMap;
import java.util.Map;

public record ManualSuiteAgentDiagnostic(
    String code,
    String severity,
    String message,
    Map<String, Object> metadata
) {

    public ManualSuiteAgentDiagnostic {
        severity = severity == null || severity.isBlank() ? "ERROR" : severity;
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }

    public static ManualSuiteAgentDiagnostic error(String code, String message, Map<String, Object> metadata) {
        return new ManualSuiteAgentDiagnostic(code, "ERROR", message, metadata);
    }
}
