package com.probeflow.testagent.manualsuiteagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ManualSuiteAgentRedactor {

    public Object redact(Object value) {
        return redactValue("", value);
    }

    public Map<String, Object> redactMap(Map<String, Object> source) {
        @SuppressWarnings("unchecked")
        var redacted = (Map<String, Object>) redact(source);
        return redacted;
    }

    private Object redactValue(String key, Object value) {
        if (sensitiveKey(key)) {
            return "[REDACTED]";
        }
        if (value instanceof Map<?, ?> map) {
            var redacted = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                var entryKey = String.valueOf(entry.getKey());
                redacted.put(entryKey, redactValue(entryKey, entry.getValue()));
            }
            return redacted;
        }
        if (value instanceof List<?> list) {
            var redacted = new ArrayList<>();
            for (var item : list) {
                redacted.add(redactValue("", item));
            }
            return redacted;
        }
        if (value instanceof String text && text.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return "Bearer [REDACTED]";
        }
        return value;
    }

    private boolean sensitiveKey(String key) {
        var normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("authorization")
            || normalized.contains("cookie")
            || normalized.contains("password")
            || normalized.contains("secret")
            || normalized.contains("token")
            || normalized.contains("apikey")
            || normalized.contains("api_key")
            || normalized.contains("credential");
    }
}
