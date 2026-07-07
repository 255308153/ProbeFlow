package com.probeflow.testagent.suiteruntime;

import java.util.LinkedHashMap;
import java.util.Map;

public record RuntimeDiagnostic(
    String code,
    String message,
    String stepId,
    String location,
    String scope,
    String path,
    String expression
) {

    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        put(map, "code", code);
        put(map, "message", message);
        put(map, "stepId", stepId);
        put(map, "location", location);
        put(map, "scope", scope);
        put(map, "path", path);
        put(map, "expression", expression);
        return map;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, RuntimeRedactor.redact(value, key));
        }
    }
}
