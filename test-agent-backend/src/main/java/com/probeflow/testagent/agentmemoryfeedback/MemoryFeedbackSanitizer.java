package com.probeflow.testagent.agentmemoryfeedback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MemoryFeedbackSanitizer {

    public static final String MASKED_VALUE = "***MASKED***";

    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile(
        "(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[^\\s,;]+"
    );
    private static final Pattern SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?i)((?:api[_-]?key|token|secret|password|cookie)\\s*[:=]\\s*)[^\\s,;&]+"
    );
    private static final Pattern SECRET_WORD_PATTERN = Pattern.compile(
        "(?i)\\b(?:secret|token|password|cookie|apikey|api-key)[-_][A-Za-z0-9._-]+"
    );

    public String sanitizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return value == null ? null : value.trim();
        }
        var sanitized = value.trim();
        sanitized = AUTHORIZATION_PATTERN.matcher(sanitized).replaceAll("$1" + MASKED_VALUE);
        sanitized = SECRET_ASSIGNMENT_PATTERN.matcher(sanitized).replaceAll("$1" + MASKED_VALUE);
        sanitized = SECRET_WORD_PATTERN.matcher(sanitized).replaceAll(MASKED_VALUE);
        return sanitized;
    }

    public Map<String, Object> sanitizeMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        var sanitized = new LinkedHashMap<String, Object>();
        value.forEach((key, item) -> {
            if (StringUtils.hasText(key) && item != null) {
                sanitized.put(key.trim(), sanitizeValue(key, item));
            }
        });
        return Map.copyOf(sanitized);
    }

    public Object sanitizeValue(String key, Object value) {
        if (isSensitiveKey(key)) {
            return MASKED_VALUE;
        }
        if (value instanceof String stringValue) {
            return sanitizeText(stringValue);
        }
        if (value instanceof Map<?, ?> nestedMap) {
            var sanitized = new LinkedHashMap<String, Object>();
            nestedMap.forEach((nestedKey, nestedValue) -> {
                if (nestedKey != null && nestedValue != null) {
                    var stringKey = nestedKey.toString();
                    sanitized.put(stringKey, sanitizeValue(stringKey, nestedValue));
                }
            });
            return Map.copyOf(sanitized);
        }
        if (value instanceof Iterable<?> iterable) {
            var sanitized = new ArrayList<Object>();
            for (var item : iterable) {
                if (item != null) {
                    sanitized.add(sanitizeValue("", item));
                }
            }
            return ListCopy.copyOf(sanitized);
        }
        return value;
    }

    public boolean containsSensitiveText(Object value) {
        return value != null && value.toString().contains(MASKED_VALUE);
    }

    private boolean isSensitiveKey(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        var normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("token")
            || normalized.contains("secret")
            || normalized.contains("cookie")
            || normalized.contains("authorization")
            || normalized.contains("password")
            || normalized.contains("apikey");
    }

    private static final class ListCopy {
        private static java.util.List<Object> copyOf(java.util.List<Object> values) {
            return java.util.List.copyOf(values);
        }
    }
}
