package com.probeflow.testagent.suiteruntime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.httpexecution.HttpClientResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ResponseExtractor {

    private final ObjectMapper objectMapper;

    public ResponseExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ExtractedVariable> extract(String stepId, Map<String, Object> step, HttpClientResponse response) {
        var results = new ArrayList<ExtractedVariable>();
        for (var rule : extractRules(step)) {
            results.add(extractRule(stepId, rule, response));
        }
        return results;
    }

    private ExtractedVariable extractRule(String stepId, Map<String, Object> rule, HttpClientResponse response) {
        var sourceType = normalizedSourceType(rule.getOrDefault("sourceType", "BODY_JSON"));
        var sourcePath = stringValue(rule.get("sourcePath"));
        var targetScope = normalizedScope(rule.get("targetScope"));
        var targetKey = stringValue(rule.get("targetKey"));
        var required = booleanValue(rule.getOrDefault("required", true));
        var failureStrategy = stringValue(rule.getOrDefault("failureStrategy", "FAIL_FAST")).toUpperCase(Locale.ROOT);
        if (targetScope == null || targetScope.isBlank()) {
            return failure(stepId, sourceType, sourcePath, targetScope, targetKey, true, failureStrategy,
                "INVALID_EXTRACT_RULE", "ExtractRule targetScope is required");
        }
        if (targetKey == null || targetKey.isBlank()) {
            return failure(stepId, sourceType, sourcePath, targetScope, targetKey, true, failureStrategy,
                "INVALID_EXTRACT_RULE", "ExtractRule targetKey is required");
        }
        var value = switch (sourceType) {
            case "BODY_JSON" -> extractBodyPath(response.body(), sourcePath == null || sourcePath.isBlank() ? "$" : sourcePath);
            case "HEADER" -> extractHeaderPath(response.headers(), sourcePath);
            case "STATUS_CODE" -> PathAccess.PathResult.found(response.statusCode());
            default -> null;
        };
        if (value == null) {
            return failure(stepId, sourceType, sourcePath, targetScope, targetKey, required, failureStrategy,
                "UNSUPPORTED_EXTRACT_SOURCE", "Unsupported extract source type: " + sourceType);
        }
        if (!value.found()) {
            return fallbackOrFailure(stepId, sourceType, sourcePath, targetScope, targetKey, required, failureStrategy, rule,
                value.failureCode(), "Unable to extract response variable");
        }
        return ExtractedVariable.success(
            stepId,
            sourceType,
            normalizedSourcePath(sourceType, sourcePath),
            targetScope,
            targetKey,
            value.value(),
            required,
            failureStrategy,
            false
        );
    }

    private PathAccess.PathResult extractBodyPath(Object body, String sourcePath) {
        var normalizedBody = normalizeBody(body);
        if ("$".equals(sourcePath)) {
            return PathAccess.PathResult.found(normalizedBody);
        }
        if (!sourcePath.startsWith("$.")) {
            return PathAccess.PathResult.missing("INVALID_BODY_JSON_PATH");
        }
        return PathAccess.resolve(normalizedBody, sourcePath.substring(2));
    }

    private Object normalizeBody(Object body) {
        if (body instanceof CharSequence text) {
            try {
                return objectMapper.readValue(text.toString(), Object.class);
            } catch (JsonProcessingException exception) {
                return body;
            }
        }
        return body;
    }

    private PathAccess.PathResult extractHeaderPath(Map<String, Object> headers, String sourcePath) {
        if (headers == null || headers.isEmpty()) {
            return PathAccess.PathResult.missing("HEADER_MISSING");
        }
        var headerName = headerName(sourcePath);
        if (headerName == null || headerName.isBlank()) {
            return PathAccess.PathResult.missing("INVALID_HEADER_PATH");
        }
        for (var entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(headerName)) {
                return PathAccess.PathResult.found(entry.getValue());
            }
        }
        return PathAccess.PathResult.missing("HEADER_MISSING");
    }

    private String headerName(String sourcePath) {
        if (sourcePath == null) {
            return null;
        }
        if (sourcePath.regionMatches(true, 0, "headers.", 0, "headers.".length())) {
            return sourcePath.substring("headers.".length());
        }
        return sourcePath;
    }

    private String normalizedSourcePath(String sourceType, String sourcePath) {
        if ("HEADER".equals(sourceType)) {
            return headerName(sourcePath);
        }
        if ("STATUS_CODE".equals(sourceType) && (sourcePath == null || sourcePath.isBlank())) {
            return "statusCode";
        }
        return sourcePath;
    }

    private ExtractedVariable fallbackOrFailure(
        String stepId,
        String sourceType,
        String sourcePath,
        String targetScope,
        String targetKey,
        boolean required,
        String failureStrategy,
        Map<String, Object> rule,
        String code,
        String message
    ) {
        if ("WRITE_NULL".equals(failureStrategy)) {
            return ExtractedVariable.success(
                stepId,
                sourceType,
                normalizedSourcePath(sourceType, sourcePath),
                targetScope,
                targetKey,
                null,
                required,
                failureStrategy,
                true
            );
        }
        if ("WRITE_DEFAULT".equals(failureStrategy)) {
            return ExtractedVariable.success(
                stepId,
                sourceType,
                normalizedSourcePath(sourceType, sourcePath),
                targetScope,
                targetKey,
                rule.get("defaultValue"),
                required,
                failureStrategy,
                true
            );
        }
        return failure(stepId, sourceType, sourcePath, targetScope, targetKey, required, failureStrategy, code, message);
    }

    private ExtractedVariable failure(
        String stepId,
        String sourceType,
        String sourcePath,
        String targetScope,
        String targetKey,
        boolean required,
        String failureStrategy,
        String code,
        String message
    ) {
        var diagnostic = new LinkedHashMap<String, Object>();
        diagnostic.put("code", code);
        diagnostic.put("message", message);
        diagnostic.put("stepId", stepId);
        diagnostic.put("sourceType", sourceType);
        diagnostic.put("sourcePath", sourcePath);
        diagnostic.put("targetScope", targetScope);
        diagnostic.put("targetKey", targetKey);
        diagnostic.put("required", required);
        diagnostic.put("failureStrategy", failureStrategy);
        var blocking = required || "FAIL_FAST".equals(failureStrategy);
        return ExtractedVariable.failure(blocking, stepId, sourceType, sourcePath, targetScope, targetKey, required, failureStrategy, diagnostic);
    }

    private List<Map<String, Object>> extractRules(Map<String, Object> step) {
        var value = step.get("extractRules");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var rules = new ArrayList<Map<String, Object>>();
        for (var item : list) {
            if (item instanceof Map<?, ?> map) {
                var copy = new LinkedHashMap<String, Object>();
                map.forEach((key, mapValue) -> {
                    if (key != null) {
                        copy.put(key.toString(), mapValue);
                    }
                });
                rules.add(copy);
            }
        }
        return rules;
    }

    private String normalizedScope(Object value) {
        var scope = stringValue(value);
        return scope == null ? null : scope.toLowerCase(Locale.ROOT);
    }

    private String normalizedSourceType(Object value) {
        var sourceType = stringValue(value);
        return sourceType == null || sourceType.isBlank() ? "BODY_JSON" : sourceType.toUpperCase(Locale.ROOT);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }
}
