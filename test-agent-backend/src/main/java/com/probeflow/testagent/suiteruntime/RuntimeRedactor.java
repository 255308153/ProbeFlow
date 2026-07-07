package com.probeflow.testagent.suiteruntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RuntimeRedactor {

    private RuntimeRedactor() {
    }

    public static Object redact(Object value, String key) {
        if (sensitiveKey(key)) {
            return "[REDACTED]";
        }
        if (value instanceof Map<?, ?> incoming) {
            var redacted = new LinkedHashMap<String, Object>();
            incoming.forEach((mapKey, mapValue) -> {
                if (mapKey != null) {
                    redacted.put(mapKey.toString(), redact(mapValue, mapKey.toString()));
                }
            });
            return redacted;
        }
        if (value instanceof List<?> incoming) {
            return incoming.stream()
                .map(item -> redact(item, key))
                .toList();
        }
        return value;
    }

    public static Map<String, Object> valueSummary(String key, Object value) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("type", valueType(value));
        if (sensitiveKey(key)) {
            summary.put("value", "[REDACTED]");
            summary.put("redacted", true);
            return summary;
        }
        if (value == null || value instanceof Number || value instanceof Boolean) {
            summary.put("value", value);
        } else if (value instanceof CharSequence text) {
            var stringValue = text.toString();
            summary.put("value", stringValue.length() > 96 ? stringValue.substring(0, 96) : stringValue);
            summary.put("truncated", stringValue.length() > 96);
        } else if (value instanceof Map<?, ?> map) {
            summary.put("keys", map.keySet().stream().map(Object::toString).sorted().toList());
            summary.put("size", map.size());
        } else if (value instanceof List<?> list) {
            summary.put("size", list.size());
        } else {
            summary.put("value", value.toString());
        }
        summary.put("redacted", false);
        return summary;
    }

    public static boolean sensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        var normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("authorization")
            || normalized.contains("cookie")
            || normalized.contains("password")
            || normalized.contains("secret")
            || normalized.contains("token")
            || normalized.contains("api-key")
            || normalized.contains("apikey")
            || normalized.contains("api_key")
            || normalized.contains("credential");
    }

    private static String valueType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        return "string";
    }
}
