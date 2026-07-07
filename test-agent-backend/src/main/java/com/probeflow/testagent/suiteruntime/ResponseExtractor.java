package com.probeflow.testagent.suiteruntime;

import com.probeflow.testagent.httpexecution.HttpClientResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ResponseExtractor {

    public List<ExtractedVariable> extract(String stepId, Map<String, Object> step, HttpClientResponse response) {
        var results = new ArrayList<ExtractedVariable>();
        for (var rule : extractRules(step)) {
            results.add(extractRule(stepId, rule, response));
        }
        return results;
    }

    private ExtractedVariable extractRule(String stepId, Map<String, Object> rule, HttpClientResponse response) {
        var sourceType = stringValue(rule.getOrDefault("sourceType", "BODY_JSON")).toUpperCase(Locale.ROOT);
        var sourcePath = stringValue(rule.get("sourcePath"));
        var targetScope = normalizedScope(rule.getOrDefault("targetScope", "SUITE"));
        var targetKey = stringValue(rule.get("targetKey"));
        var required = booleanValue(rule.getOrDefault("required", true));
        if (!"BODY_JSON".equals(sourceType)) {
            return failure(stepId, sourceType, sourcePath, targetScope, targetKey, required,
                "UNSUPPORTED_EXTRACT_SOURCE", "Unsupported extract source type: " + sourceType);
        }
        if (targetKey == null || targetKey.isBlank()) {
            return failure(stepId, sourceType, sourcePath, targetScope, targetKey, true,
                "INVALID_EXTRACT_RULE", "ExtractRule targetKey is required");
        }
        var path = sourcePath == null || sourcePath.isBlank() ? "$" : sourcePath;
        var value = extractBodyPath(response.body(), path);
        if (!value.found()) {
            return failure(stepId, sourceType, sourcePath, targetScope, targetKey, required,
                value.failureCode(), "Unable to extract response variable");
        }
        return ExtractedVariable.success(stepId, sourceType, sourcePath, targetScope, targetKey, value.value());
    }

    private PathAccess.PathResult extractBodyPath(Object body, String sourcePath) {
        if ("$".equals(sourcePath)) {
            return PathAccess.PathResult.found(body);
        }
        if (!sourcePath.startsWith("$.")) {
            return PathAccess.PathResult.missing("INVALID_BODY_JSON_PATH");
        }
        return PathAccess.resolve(body, sourcePath.substring(2));
    }

    private ExtractedVariable failure(
        String stepId,
        String sourceType,
        String sourcePath,
        String targetScope,
        String targetKey,
        boolean blocking,
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
        return ExtractedVariable.failure(blocking, stepId, sourceType, sourcePath, targetScope, targetKey, diagnostic);
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
        return stringValue(value).toLowerCase(Locale.ROOT);
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
