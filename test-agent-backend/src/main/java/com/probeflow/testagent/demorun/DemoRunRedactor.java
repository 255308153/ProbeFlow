package com.probeflow.testagent.demorun;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class DemoRunRedactor {

    static final String REDACTED = "[REDACTED]";

    private static final Pattern BEARER_OR_BASIC = Pattern.compile(
        "(?i).*\\b(?:bearer|basic)\\s+[^\\s,;}\\]]+.*"
    );
    private static final Pattern ASSIGNED_SECRET = Pattern.compile(
        "(?i).*\\b(?:authorization|cookie|password|secret|token|api[-_]?key|apikey|model[-_]?key|credential)\\b\\s*[:=]\\s*[^\\s,;}\\]]+.*"
    );
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile("(?i).*\\bsk-[a-z0-9][a-z0-9_-]{6,}.*");

    private DemoRunRedactor() {
    }

    static Object sanitize(Object value) {
        return sanitize(value, "");
    }

    static Object sanitize(Object value, String key) {
        if (sensitiveKey(key)) {
            return REDACTED;
        }
        if (value instanceof Map<?, ?> map) {
            var sanitized = new LinkedHashMap<String, Object>();
            map.forEach((childKey, child) -> {
                var keyText = String.valueOf(childKey);
                sanitized.put(keyText, sanitize(child, keyText));
            });
            return sanitized;
        }
        if (value instanceof Iterable<?> values) {
            var sanitized = new java.util.ArrayList<Object>();
            values.forEach(child -> sanitized.add(sanitize(child, key)));
            return sanitized;
        }
        if (value instanceof String text) {
            return sanitizeText(text);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sanitizeMap(Map<String, Object> map) {
        return (Map<String, Object>) sanitize(map == null ? Map.of() : map);
    }

    static String sanitizeText(String text) {
        if (text == null) {
            return null;
        }
        return sensitiveValue(text) ? REDACTED : text;
    }

    static List<DemoRunLlmCallSummary> sanitizeLlmCalls(List<DemoRunLlmCallSummary> llmCalls) {
        if (llmCalls == null || llmCalls.isEmpty()) {
            return List.of();
        }
        return llmCalls.stream()
            .map(call -> new DemoRunLlmCallSummary(
                sanitizeText(call.purpose()),
                sanitizeText(call.status()),
                sanitizeText(call.provider()),
                sanitizeText(call.model()),
                call.promptTokens(),
                call.completionTokens(),
                call.totalTokens(),
                sanitizeText(call.errorType()),
                sanitizeText(call.errorMessage()),
                sanitizeMap(call.metadata())
            ))
            .toList();
    }

    static boolean sensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        var normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return normalized.contains("authorization")
            || normalized.contains("cookie")
            || normalized.contains("password")
            || normalized.contains("secret")
            || normalized.contains("token")
            || normalized.contains("apikey")
            || normalized.contains("credential")
            || normalized.contains("modelkey")
            || normalized.contains("llmkey");
    }

    static boolean sensitiveValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return BEARER_OR_BASIC.matcher(value).matches()
            || ASSIGNED_SECRET.matcher(value).matches()
            || OPENAI_STYLE_KEY.matcher(value).matches()
            || value.toLowerCase(Locale.ROOT).contains("unredacted-fixture-secret");
    }
}
