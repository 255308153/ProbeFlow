package com.probeflow.testagent.httpexecution;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.testcase.TestCase;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ExecutableRequestBuilder {

    private static final Pattern DOUBLE_BRACE_PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_.-]+)}}");
    private static final Pattern SINGLE_BRACE_PLACEHOLDER = Pattern.compile("(?<!\\{)\\{([A-Za-z0-9_.-]+)}(?!})");

    public ExecutableRequestBuildResult build(TestCase testCase, ApiSpec apiSpec, HttpExecutionRequest executionRequest) {
        var values = mergedValues(executionRequest);
        var missingVariables = new ArrayList<String>();
        var requestShape = requestShape(testCase);

        var method = stringValue(resolveValue(requestShape.get("method"), values, missingVariables));
        if (!StringUtils.hasText(method)) {
            method = apiSpec.getHttpMethod().name();
        }
        var path = stringValue(resolveValue(requestShape.get("path"), values, missingVariables));
        if (!StringUtils.hasText(path)) {
            path = stringValue(resolveValue(apiSpec.getPath(), values, missingVariables));
        }
        var headers = objectMap(resolveValue(requestShape.get("headers"), values, missingVariables));
        var queryParams = objectMap(resolveValue(firstPresent(requestShape, "queryParams", "query"), values, missingVariables));
        var body = resolveValue(requestShape.get("body"), values, missingVariables);
        applyAuthFallback(headers, apiSpec, values, missingVariables);

        var url = buildUrl(baseUrl(executionRequest), path, queryParams);
        var snapshot = requestSnapshot(testCase, method, path, url, headers, queryParams, body);
        if (!missingVariables.isEmpty()) {
            return ExecutableRequestBuildResult.blocked("Unresolved variable: " + missingVariables.getFirst(), snapshot);
        }

        var protocolError = protocolError(url);
        if (protocolError != null) {
            return ExecutableRequestBuildResult.blocked(protocolError, snapshot);
        }

        return ExecutableRequestBuildResult.ready(
            new HttpClientRequest(method, path, url, headers, queryParams, body),
            snapshot
        );
    }

    private Map<String, Object> mergedValues(HttpExecutionRequest request) {
        var values = new LinkedHashMap<String, Object>();
        values.putAll(request.environmentVariables());
        values.putAll(request.authVariables());
        return values;
    }

    private String baseUrl(HttpExecutionRequest request) {
        var value = firstPresent(request.environmentVariables(), "baseUrl", "base_url", "BASE_URL");
        return value == null ? null : value.toString();
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        for (var key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private void applyAuthFallback(
        Map<String, Object> headers,
        ApiSpec apiSpec,
        Map<String, Object> values,
        List<String> missingVariables
    ) {
        var auth = apiSpec.getAuth();
        if (auth == null || auth.isEmpty()) {
            return;
        }
        var type = stringValue(auth.get("type"));
        if (!"bearer".equalsIgnoreCase(type)) {
            return;
        }
        var headerName = stringValue(auth.getOrDefault("header", "Authorization"));
        if (headers.containsKey(headerName)) {
            return;
        }
        var tokenVariable = stringValue(auth.getOrDefault("tokenVariable", "authToken"));
        var token = values.get(tokenVariable);
        if (token == null) {
            addMissing(missingVariables, tokenVariable);
            return;
        }
        headers.put(headerName, "Bearer " + token);
    }

    private String buildUrl(String baseUrl, String path, Map<String, Object> queryParams) {
        var resolvedPath = StringUtils.hasText(path) ? path : "";
        var withBase = resolvedPath;
        if (StringUtils.hasText(baseUrl) && !hasScheme(resolvedPath)) {
            withBase = trimTrailingSlash(baseUrl) + "/" + trimLeadingSlash(resolvedPath);
        }
        var queryString = queryString(queryParams);
        if (!StringUtils.hasText(queryString)) {
            return withBase;
        }
        return withBase + (withBase.contains("?") ? "&" : "?") + queryString;
    }

    private String queryString(Map<String, Object> queryParams) {
        if (queryParams.isEmpty()) {
            return "";
        }
        var parts = new ArrayList<String>();
        queryParams.forEach((key, value) -> {
            if (value != null) {
                parts.add(urlEncode(key) + "=" + urlEncode(value.toString()));
            }
        });
        return String.join("&", parts);
    }

    private String protocolError(String url) {
        if (!StringUtils.hasText(url) || !hasScheme(url)) {
            return null;
        }
        try {
            var uri = URI.create(url);
            var scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return "Unsupported protocol: " + scheme.toLowerCase(Locale.ROOT);
            }
            return null;
        } catch (IllegalArgumentException exception) {
            return "Invalid URL: " + url;
        }
    }

    private boolean hasScheme(String value) {
        return value != null && value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    }

    private String trimTrailingSlash(String value) {
        var result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String trimLeadingSlash(String value) {
        var result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    private Object resolveValue(Object value, Map<String, Object> values, List<String> missingVariables) {
        if (value instanceof String text) {
            return resolvePlaceholders(text, values, missingVariables);
        }
        if (value instanceof Map<?, ?> incoming) {
            var resolved = new LinkedHashMap<String, Object>();
            incoming.forEach((key, mapValue) -> {
                if (key != null) {
                    resolved.put(key.toString(), resolveValue(mapValue, values, missingVariables));
                }
            });
            return resolved;
        }
        if (value instanceof List<?> incoming) {
            return incoming.stream()
                .map(item -> resolveValue(item, values, missingVariables))
                .toList();
        }
        return value;
    }

    private String resolvePlaceholders(String text, Map<String, Object> values, List<String> missingVariables) {
        var resolved = replacePlaceholders(text, values, missingVariables, DOUBLE_BRACE_PLACEHOLDER);
        return replacePlaceholders(resolved, values, missingVariables, SINGLE_BRACE_PLACEHOLDER);
    }

    private String replacePlaceholders(
        String text,
        Map<String, Object> values,
        List<String> missingVariables,
        Pattern pattern
    ) {
        var matcher = pattern.matcher(text);
        var result = new StringBuffer();
        while (matcher.find()) {
            var variableName = matcher.group(1);
            var variableValue = values.get(variableName);
            if (variableValue == null) {
                addMissing(missingVariables, variableName);
                matcher.appendReplacement(result, matcher.group(0));
            } else {
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(variableValue.toString()));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void addMissing(List<String> missingVariables, String variableName) {
        if (!missingVariables.contains(variableName)) {
            missingVariables.add(variableName);
        }
    }

    private Map<String, Object> requestShape(TestCase testCase) {
        var detailShape = objectMap(testCase.getDetail().get("requestShape"));
        if (!detailShape.isEmpty()) {
            return detailShape;
        }
        if (!testCase.getSteps().isEmpty()) {
            return objectMap(testCase.getSteps().getFirst().get("requestShape"));
        }
        return Map.of();
    }

    private Map<String, Object> requestSnapshot(
        TestCase testCase,
        String method,
        String path,
        String url,
        Map<String, Object> headers,
        Map<String, Object> queryParams,
        Object body
    ) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("caseId", testCase.getCaseId());
        snapshot.put("apiSpecId", testCase.getPrimaryApiSpecId());
        snapshot.put("method", method);
        snapshot.put("path", path);
        snapshot.put("url", url);
        snapshot.put("headers", redactSecrets(headers, "headers"));
        if (!queryParams.isEmpty()) {
            snapshot.put("queryParams", redactSecrets(queryParams, "queryParams"));
        }
        if (body != null) {
            snapshot.put("body", redactSecrets(body, "body"));
        }
        return snapshot;
    }

    private Object redactSecrets(Object value, String key) {
        if (sensitiveKey(key)) {
            return "[REDACTED]";
        }
        if (value instanceof Map<?, ?> incoming) {
            var redacted = new LinkedHashMap<String, Object>();
            incoming.forEach((mapKey, mapValue) -> {
                if (mapKey != null) {
                    redacted.put(mapKey.toString(), redactSecrets(mapValue, mapKey.toString()));
                }
            });
            return redacted;
        }
        if (value instanceof List<?> incoming) {
            return incoming.stream()
                .map(item -> redactSecrets(item, key))
                .toList();
        }
        return value;
    }

    private boolean sensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        var normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("authorization")
            || normalized.contains("token")
            || normalized.contains("secret")
            || normalized.contains("password")
            || normalized.contains("api-key")
            || normalized.contains("apikey")
            || normalized.contains("cookie");
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> incoming)) {
            return new LinkedHashMap<>();
        }
        var copied = new LinkedHashMap<String, Object>();
        incoming.forEach((key, mapValue) -> {
            if (key != null) {
                copied.put(key.toString(), mapValue);
            }
        });
        return copied;
    }
}
