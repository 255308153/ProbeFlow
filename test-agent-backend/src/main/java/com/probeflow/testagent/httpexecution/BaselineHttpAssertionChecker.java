package com.probeflow.testagent.httpexecution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.testcase.TestCase;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BaselineHttpAssertionChecker {

    private static final String PASSED = "PASSED";
    private static final String FAILED = "FAILED";

    private final ObjectMapper objectMapper;

    public BaselineHttpAssertionChecker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> check(TestCase testCase, HttpClientResponse response, long durationMs) {
        var results = new ArrayList<Map<String, Object>>();
        expectedStatus(testCase).ifPresent(expected -> results.add(statusCodeResult(expected, response.statusCode())));
        for (var definition : assertionDefinitions(testCase)) {
            addDefinitionResult(results, definition, response, durationMs);
        }
        return List.copyOf(results);
    }

    public List<Map<String, Object>> checkStep(
        Map<String, Object> step,
        HttpClientResponse response,
        long durationMs
    ) {
        var results = new ArrayList<Map<String, Object>>();
        expectedStatus(step).ifPresent(expected -> results.add(statusCodeResult(expected, response.statusCode())));
        for (var definition : assertionDefinitions(step)) {
            addDefinitionResult(results, definition, response, durationMs);
        }
        return List.copyOf(results);
    }

    private void addDefinitionResult(
        List<Map<String, Object>> results,
        Map<String, Object> definition,
        HttpClientResponse response,
        long durationMs
    ) {
            var type = assertionType(definition);
            if (!StringUtils.hasText(type)) {
                return;
            }
            switch (type) {
                case "STATUS_CODE", "EXPECTED_STATUS", "HTTP_STATUS" ->
                    results.add(statusCodeResult(integerValue(expectedValue(definition)), response.statusCode(), definition));
                case "BODY_PRESENT", "RESPONSE_BODY_PRESENT" ->
                    results.add(bodyPresenceResult(definition, response.body()));
                case "JSON_FIELD_EXISTS", "JSON_PATH_EXISTS" ->
                    results.add(jsonFieldExistsResult(definition, response.body()));
                case "JSON_FIELD_EQUALS", "JSON_PATH_EQUALS" ->
                    results.add(jsonFieldEqualsResult(definition, response.body()));
                case "DURATION_LESS_THAN_MS", "DURATION_UNDER_MS", "MAX_DURATION_MS" ->
                    results.add(durationResult(definition, durationMs));
                default -> {
                    var result = baseResult(assertionName(definition, type), type, null, null, critical(definition));
                    result.put("status", FAILED);
                    result.put("message", "Unsupported baseline assertion type: " + type);
                    results.add(result);
                }
            }
    }

    private java.util.Optional<Integer> expectedStatus(TestCase testCase) {
        var value = testCase.getDetail().get("expectedStatus");
        if (value == null && !testCase.getSteps().isEmpty()) {
            value = testCase.getSteps().getFirst().get("expectedStatus");
        }
        return value == null ? java.util.Optional.empty() : java.util.Optional.of(integerValue(value));
    }

    private java.util.Optional<Integer> expectedStatus(Map<String, Object> step) {
        var value = step.get("expectedStatus");
        return value == null ? java.util.Optional.empty() : java.util.Optional.of(integerValue(value));
    }

    private List<Map<String, Object>> assertionDefinitions(TestCase testCase) {
        return assertionDefinitions(testCase.getDetail());
    }

    private List<Map<String, Object>> assertionDefinitions(Map<String, Object> source) {
        var definitions = new ArrayList<Map<String, Object>>();
        addDefinitions(definitions, source.get("assertions"));
        addDefinitions(definitions, source.get("assertionDefinitions"));
        return definitions;
    }

    private void addDefinitions(List<Map<String, Object>> definitions, Object value) {
        if (value instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof Map<?, ?> map) {
                    var definition = new LinkedHashMap<String, Object>();
                    map.forEach((key, mapValue) -> {
                        if (key != null) {
                            definition.put(key.toString(), mapValue);
                        }
                    });
                    definitions.add(definition);
                }
            }
        }
    }

    private Map<String, Object> statusCodeResult(int expected, int actual) {
        return statusCodeResult(expected, actual, Map.of("critical", true));
    }

    private Map<String, Object> statusCodeResult(int expected, int actual, Map<String, Object> definition) {
        var result = baseResult(assertionName(definition, "expected status code"), "STATUS_CODE", expected, actual, critical(definition));
        var passed = expected == actual;
        result.put("status", passed ? PASSED : FAILED);
        if (!passed) {
            result.put("message", "Expected HTTP status " + expected + " but got " + actual);
        }
        return result;
    }

    private Map<String, Object> bodyPresenceResult(Map<String, Object> definition, Object body) {
        var expected = booleanValue(expectedValue(definition), true);
        var actual = bodyPresent(body);
        var result = baseResult(assertionName(definition, "response body present"), "BODY_PRESENT", expected, actual, critical(definition));
        var passed = expected == actual;
        result.put("status", passed ? PASSED : FAILED);
        if (!passed) {
            result.put("message", expected ? "Expected response body to be present" : "Expected response body to be absent");
        }
        return result;
    }

    private Map<String, Object> jsonFieldExistsResult(Map<String, Object> definition, Object body) {
        var path = path(definition);
        var lookup = lookup(body, path);
        var result = baseResult(assertionName(definition, "json field exists"), "JSON_FIELD_EXISTS", true, lookup.found(), critical(definition));
        result.put("path", path);
        result.put("status", lookup.found() ? PASSED : FAILED);
        if (StringUtils.hasText(lookup.message())) {
            result.put("message", lookup.message());
        }
        return result;
    }

    private Map<String, Object> jsonFieldEqualsResult(Map<String, Object> definition, Object body) {
        var path = path(definition);
        var expected = expectedValue(definition);
        var lookup = lookup(body, path);
        var result = baseResult(assertionName(definition, "json field equals"), "JSON_FIELD_EQUALS", expected, lookup.value(), critical(definition));
        result.put("path", path);
        var passed = lookup.found() && scalarEquals(expected, lookup.value());
        result.put("status", passed ? PASSED : FAILED);
        if (!lookup.found() && StringUtils.hasText(lookup.message())) {
            result.put("message", lookup.message());
        } else if (!passed) {
            result.put("message", "Expected JSON field " + path + " to equal " + expected);
        }
        return result;
    }

    private Map<String, Object> durationResult(Map<String, Object> definition, long durationMs) {
        var expected = longValue(firstPresent(definition, "expected", "expectedValue", "thresholdMs", "maxMs"));
        var actual = durationMs;
        var result = baseResult(assertionName(definition, "duration threshold"), "DURATION_LESS_THAN_MS", expected, actual, critical(definition));
        var passed = actual <= expected;
        result.put("status", passed ? PASSED : FAILED);
        if (!passed) {
            result.put("message", "Expected duration <= " + expected + "ms but got " + actual + "ms");
        }
        return result;
    }

    private Map<String, Object> baseResult(
        String name,
        String type,
        Object expected,
        Object actual,
        boolean critical
    ) {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("type", type);
        result.put("expected", expected);
        result.put("actual", actual);
        result.put("critical", critical);
        return result;
    }

    private boolean bodyPresent(Object body) {
        if (body == null) {
            return false;
        }
        if (body instanceof CharSequence text) {
            return !text.isEmpty();
        }
        if (body instanceof byte[] bytes) {
            return bytes.length > 0;
        }
        return true;
    }

    private JsonLookup lookup(Object body, String path) {
        var root = jsonRoot(body);
        if (!root.validJson()) {
            return JsonLookup.notFound("Response body is not valid JSON");
        }
        if (!StringUtils.hasText(path)) {
            return JsonLookup.notFound("JSON field path is required");
        }

        Object current = root.value();
        for (var token : pathTokens(path)) {
            var next = descend(current, token);
            if (!next.found()) {
                return JsonLookup.notFound("JSON field not found: " + path);
            }
            current = next.value();
        }
        return JsonLookup.found(current);
    }

    private JsonRoot jsonRoot(Object body) {
        if (body instanceof Map<?, ?> || body instanceof List<?>) {
            return JsonRoot.valid(body);
        }
        if (body instanceof CharSequence text) {
            try {
                return JsonRoot.valid(objectMapper.readValue(text.toString(), Object.class));
            } catch (JsonProcessingException exception) {
                return JsonRoot.invalid();
            }
        }
        return JsonRoot.invalid();
    }

    private List<String> pathTokens(String path) {
        var normalized = path.trim();
        if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        return List.of(normalized.split("\\."));
    }

    private JsonLookup descend(Object current, String token) {
        var remaining = token;
        if (!remaining.contains("[")) {
            return descendSimple(current, remaining);
        }

        var bracketStart = remaining.indexOf('[');
        var fieldName = remaining.substring(0, bracketStart);
        var lookup = StringUtils.hasText(fieldName)
            ? descendSimple(current, fieldName)
            : JsonLookup.found(current);
        if (!lookup.found()) {
            return lookup;
        }
        current = lookup.value();
        remaining = remaining.substring(bracketStart);
        while (remaining.startsWith("[")) {
            var bracketEnd = remaining.indexOf(']');
            if (bracketEnd < 0) {
                return JsonLookup.notFound(null);
            }
            var indexText = remaining.substring(1, bracketEnd);
            if (!(current instanceof List<?> list)) {
                return JsonLookup.notFound(null);
            }
            var index = integerValue(indexText);
            if (index < 0 || index >= list.size()) {
                return JsonLookup.notFound(null);
            }
            current = list.get(index);
            remaining = remaining.substring(bracketEnd + 1);
        }
        return JsonLookup.found(current);
    }

    private JsonLookup descendSimple(Object current, String token) {
        if (current instanceof Map<?, ?> map) {
            return map.containsKey(token) ? JsonLookup.found(map.get(token)) : JsonLookup.notFound(null);
        }
        if (current instanceof List<?> list && token.matches("\\d+")) {
            var index = integerValue(token);
            return index >= 0 && index < list.size() ? JsonLookup.found(list.get(index)) : JsonLookup.notFound(null);
        }
        return JsonLookup.notFound(null);
    }

    private boolean scalarEquals(Object expected, Object actual) {
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            return new BigDecimal(expectedNumber.toString()).compareTo(new BigDecimal(actualNumber.toString())) == 0;
        }
        return Objects.equals(expected, actual);
    }

    private String assertionType(Map<String, Object> definition) {
        var value = firstPresent(definition, "type", "assertionType");
        return value == null ? "" : value.toString().trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private String assertionName(Map<String, Object> definition, String fallback) {
        var value = firstPresent(definition, "name", "assertionName");
        return value == null || !StringUtils.hasText(value.toString()) ? fallback : value.toString();
    }

    private String path(Map<String, Object> definition) {
        var value = firstPresent(definition, "path", "jsonPath", "fieldPath", "sourcePath");
        return value == null ? "" : value.toString();
    }

    private Object expectedValue(Map<String, Object> definition) {
        return firstPresent(definition, "expected", "expectedValue", "value");
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        for (var key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private boolean critical(Map<String, Object> definition) {
        return booleanValue(definition.get("critical"), true);
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private int integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private record JsonRoot(boolean validJson, Object value) {
        static JsonRoot valid(Object value) {
            return new JsonRoot(true, value);
        }

        static JsonRoot invalid() {
            return new JsonRoot(false, null);
        }
    }

    private record JsonLookup(boolean found, Object value, String message) {
        static JsonLookup found(Object value) {
            return new JsonLookup(true, value, null);
        }

        static JsonLookup notFound(String message) {
            return new JsonLookup(false, null, message);
        }
    }
}
