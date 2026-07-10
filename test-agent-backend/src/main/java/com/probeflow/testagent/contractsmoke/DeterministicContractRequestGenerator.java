package com.probeflow.testagent.contractsmoke;

import com.probeflow.testagent.apispec.ApiSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class DeterministicContractRequestGenerator {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");

    Map<String, Object> generate(ApiSpec apiSpec, String profile) {
        var pathValues = generateSectionValues(apiSpec, "path", profile);
        var queryValues = generateSectionValues(apiSpec, "query", profile);
        var headerValues = generateSectionValues(apiSpec, "header", profile);
        var body = generateBody(apiSpec, profile);

        var resolvedPath = resolvePath(apiSpec.getPath(), pathValues);
        var request = new LinkedHashMap<String, Object>();
        request.put("method", apiSpec.getHttpMethod().name());
        request.put("path", resolvedPath);
        request.put("headers", headerValues);
        request.put("queryParams", queryValues);
        if (body != null) {
            request.put("body", body);
            headerValues.putIfAbsent("Content-Type", requestContentType(apiSpec));
        }
        request.put("pathParams", pathValues);
        request.put("generation", Map.of(
            "generator", "openapi-contract-smoke",
            "profile", profile,
            "deterministic", true
        ));
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> generateSectionValues(ApiSpec apiSpec, String section, String profile) {
        var values = new LinkedHashMap<String, Object>();
        var parameters = apiSpec.getParameters() == null ? Map.<String, Object>of() : apiSpec.getParameters();
        var sectionValue = parameters.get(section);
        if (sectionValue instanceof List<?> list) {
            for (var item : list) {
                if (!(item instanceof Map<?, ?> parameter)) {
                    continue;
                }
                var name = stringValue(parameter.get("name"));
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                var required = Boolean.TRUE.equals(parameter.get("required")) || "path".equals(section);
                if (!required && !"path".equals(section)) {
                    // smoke only materializes required non-path params; path vars always filled
                    continue;
                }
                var schema = parameter.get("schema") instanceof Map<?, ?> schemaMap
                    ? castMap(schemaMap)
                    : Map.<String, Object>of("type", "string");
                values.put(name, valueFor(schema, section + "." + name, profile, apiSpec));
            }
            return values;
        }
        if (sectionValue instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                var name = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> metadata)) {
                    values.put(name, scalarFallback(name, profile));
                    continue;
                }
                var meta = castMap(metadata);
                var required = Boolean.TRUE.equals(meta.get("required")) || "path".equals(section);
                if (!required && !"path".equals(section)) {
                    continue;
                }
                values.put(name, valueFor(meta, section + "." + name, profile, apiSpec));
            }
        }

        if ("path".equals(section)) {
            var matcher = PATH_VARIABLE.matcher(apiSpec.getPath() == null ? "" : apiSpec.getPath());
            while (matcher.find()) {
                var name = matcher.group(1);
                values.putIfAbsent(name, valueFor(Map.of("type", "string"), "path." + name, profile, apiSpec));
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Object generateBody(ApiSpec apiSpec, String profile) {
        var parameters = apiSpec.getParameters() == null ? Map.<String, Object>of() : apiSpec.getParameters();
        var requestBody = parameters.get("requestBody");
        if (!(requestBody instanceof Map<?, ?> bodyMapRaw)) {
            // generation-style body section
            if (parameters.get("body") instanceof Map<?, ?> bodyFields) {
                return objectFromFieldMap(castMap(bodyFields), "body", profile, apiSpec);
            }
            return null;
        }
        var bodyMap = castMap(bodyMapRaw);
        if (bodyMap.get("fields") instanceof List<?> fields) {
            return objectFromFieldList(fields, "requestBody", profile, apiSpec);
        }
        if (bodyMap.get("content") instanceof List<?> contentList && !contentList.isEmpty()) {
            for (var item : contentList) {
                if (item instanceof Map<?, ?> contentItem && contentItem.get("schema") instanceof Map<?, ?> schema) {
                    return valueFor(castMap(schema), "requestBody", profile, apiSpec);
                }
            }
        }
        if (bodyMap.get("schema") instanceof Map<?, ?> schema) {
            return valueFor(castMap(schema), "requestBody", profile, apiSpec);
        }
        if (bodyMap.containsKey("type") || bodyMap.containsKey("properties") || bodyMap.containsKey("enum")) {
            return valueFor(bodyMap, "requestBody", profile, apiSpec);
        }
        return null;
    }

    private Object objectFromFieldList(List<?> fields, String path, String profile, ApiSpec apiSpec) {
        var object = new LinkedHashMap<String, Object>();
        for (var field : fields) {
            if (!(field instanceof Map<?, ?> fieldMapRaw)) {
                continue;
            }
            var fieldMap = castMap(fieldMapRaw);
            var name = stringValue(fieldMap.getOrDefault("name", fieldMap.get("field")));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            var required = Boolean.TRUE.equals(fieldMap.get("required"));
            if (!required && fieldMap.get("required") != null) {
                continue;
            }
            // Spring DTO fields without required flag: include all for smoke body completeness
            object.put(name, valueFor(fieldMap, path + "." + name, profile, apiSpec));
        }
        return object.isEmpty() ? null : object;
    }

    private Object objectFromFieldMap(Map<String, Object> fields, String path, String profile, ApiSpec apiSpec) {
        var object = new LinkedHashMap<String, Object>();
        for (var entry : fields.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> metaRaw)) {
                continue;
            }
            var meta = castMap(metaRaw);
            if (meta.containsKey("required") && !Boolean.TRUE.equals(meta.get("required"))) {
                continue;
            }
            object.put(entry.getKey(), valueFor(meta, path + "." + entry.getKey(), profile, apiSpec));
        }
        return object.isEmpty() ? null : object;
    }

    @SuppressWarnings("unchecked")
    private Object valueFor(Map<String, Object> schema, String path, String profile, ApiSpec apiSpec) {
        if (schema == null || schema.isEmpty()) {
            return scalarFallback(path, profile);
        }
        var enumValues = enumValues(path, schema, apiSpec);
        if (!enumValues.isEmpty()) {
            return enumValues.getFirst();
        }
        var type = stringValue(schema.get("type"));
        if (!StringUtils.hasText(type) && schema.get("properties") instanceof Map<?, ?>) {
            type = "object";
        }
        if (!StringUtils.hasText(type) && schema.get("items") != null) {
            type = "array";
        }
        if (!StringUtils.hasText(type)) {
            type = "string";
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "integer", "int", "int32", "int64" -> integerValue(path, schema, apiSpec);
            case "number", "float", "double" -> numberValue(path, schema, apiSpec);
            case "boolean" -> true;
            case "array" -> {
                var items = schema.get("items") instanceof Map<?, ?> itemSchema
                    ? castMap(itemSchema)
                    : Map.<String, Object>of("type", "string");
                yield List.of(valueFor(items, path + "[]", profile, apiSpec));
            }
            case "object" -> {
                var object = new LinkedHashMap<String, Object>();
                var properties = schema.get("properties") instanceof Map<?, ?> props
                    ? castMap(props)
                    : Map.<String, Object>of();
                var required = new ArrayList<String>();
                if (schema.get("required") instanceof List<?> requiredList) {
                    for (var item : requiredList) {
                        if (item != null) {
                            required.add(String.valueOf(item));
                        }
                    }
                }
                if (required.isEmpty()) {
                    required.addAll(properties.keySet());
                }
                for (var name : required) {
                    var propertySchema = properties.get(name) instanceof Map<?, ?> propertyMap
                        ? castMap(propertyMap)
                        : Map.<String, Object>of("type", "string");
                    object.put(name, valueFor(propertySchema, path + "." + name, profile, apiSpec));
                }
                yield object;
            }
            default -> stringValueFor(path, schema, profile, apiSpec);
        };
    }

    private Object integerValue(String path, Map<String, Object> schema, ApiSpec apiSpec) {
        var validation = validationFor(path, apiSpec, schema);
        var minimum = numberConstraint(validation, "minimum", "min");
        var maximum = numberConstraint(validation, "maximum", "max");
        long value = minimum != null ? minimum.longValue() : 1L;
        if (maximum != null && value > maximum.longValue()) {
            value = maximum.longValue();
        }
        if (minimum != null && value < minimum.longValue()) {
            value = minimum.longValue();
        }
        return Math.toIntExact(value);
    }

    private Object numberValue(String path, Map<String, Object> schema, ApiSpec apiSpec) {
        var validation = validationFor(path, apiSpec, schema);
        var minimum = numberConstraint(validation, "minimum", "min");
        var maximum = numberConstraint(validation, "maximum", "max");
        double value = minimum != null ? minimum.doubleValue() : 1.0d;
        if (maximum != null && value > maximum.doubleValue()) {
            value = maximum.doubleValue();
        }
        if (minimum != null && value < minimum.doubleValue()) {
            value = minimum.doubleValue();
        }
        return value;
    }

    private String stringValueFor(String path, Map<String, Object> schema, String profile, ApiSpec apiSpec) {
        var validation = validationFor(path, apiSpec, schema);
        var maxLength = numberConstraint(validation, "maxLength");
        if (maxLength != null && maxLength.intValue() <= 0) {
            return "";
        }

        var base = "sample-" + leafName(path);
        if (!OpenApiContractSmokeRunRequest.DEFAULT_PROFILE.equals(profile)) {
            base = profile + "-" + base;
        }
        var minLength = numberConstraint(validation, "minLength");
        if (minLength != null && base.length() < minLength.intValue()) {
            base = base + "x".repeat(Math.max(0, minLength.intValue() - base.length()));
        }
        if (maxLength != null && base.length() > maxLength.intValue()) {
            base = base.substring(0, maxLength.intValue());
        }
        return base;
    }

    private Map<String, Object> validationFor(String path, ApiSpec apiSpec, Map<String, Object> schema) {
        var merged = new LinkedHashMap<String, Object>();
        putIfNumber(merged, "minimum", schema.get("minimum"));
        putIfNumber(merged, "maximum", schema.get("maximum"));
        putIfNumber(merged, "min", schema.get("min"));
        putIfNumber(merged, "max", schema.get("max"));
        putIfNumber(merged, "minLength", schema.get("minLength"));
        putIfNumber(merged, "maxLength", schema.get("maxLength"));
        var constraints = apiSpec.getConstraints();
        if (constraints != null && constraints.get("validations") instanceof Map<?, ?> validations) {
            for (var key : validationLookupKeys(path)) {
                var validation = validations.get(key);
                if (validation instanceof Map<?, ?> validationMap) {
                    validationMap.forEach((validationKey, value) -> {
                        if (validationKey != null) {
                            merged.putIfAbsent(String.valueOf(validationKey), value);
                        }
                    });
                }
            }
        }
        return merged;
    }

    private List<Object> enumValues(String path, Map<String, Object> schema, ApiSpec apiSpec) {
        if (schema.get("enum") instanceof List<?> schemaEnums && !schemaEnums.isEmpty()) {
            return new ArrayList<>(schemaEnums);
        }
        var constraints = apiSpec.getConstraints();
        if (constraints != null && constraints.get("enums") instanceof Map<?, ?> enums) {
            for (var key : validationLookupKeys(path)) {
                if (enums.get(key) instanceof List<?> listed && !listed.isEmpty()) {
                    return new ArrayList<>(listed);
                }
            }
        }
        return List.of();
    }

    private List<String> validationLookupKeys(String path) {
        var keys = new ArrayList<String>();
        if (!StringUtils.hasText(path)) {
            return keys;
        }
        keys.add(path);
        var leaf = leafName(path);
        if (StringUtils.hasText(leaf) && !keys.contains(leaf)) {
            keys.add(leaf);
        }
        if (!path.contains(".")) {
            keys.add("path." + path);
            keys.add("query." + path);
            keys.add("header." + path);
            keys.add("requestBody." + path);
        }
        return keys;
    }

    private Number numberConstraint(Map<String, Object> validation, String... keys) {
        for (var key : keys) {
            var value = validation.get(key);
            if (value instanceof Number number) {
                return number;
            }
            if (value != null) {
                try {
                    return Double.valueOf(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    // continue
                }
            }
        }
        return null;
    }

    private void putIfNumber(Map<String, Object> target, String key, Object value) {
        if (value instanceof Number) {
            target.put(key, value);
        } else if (value != null) {
            try {
                target.put(key, Double.valueOf(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                // ignore non-numeric constraint values
            }
        }
    }

    private String resolvePath(String pathTemplate, Map<String, Object> pathValues) {
        if (!StringUtils.hasText(pathTemplate)) {
            return "/";
        }
        var resolved = pathTemplate;
        for (var entry : pathValues.entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return resolved;
    }

    private String requestContentType(ApiSpec apiSpec) {
        var parameters = apiSpec.getParameters();
        if (parameters != null && parameters.get("requestBody") instanceof Map<?, ?> body) {
            if (body.get("content") instanceof List<?> content && !content.isEmpty()
                && content.getFirst() instanceof Map<?, ?> first
                && first.get("mediaType") != null) {
                return String.valueOf(first.get("mediaType"));
            }
        }
        return "application/json";
    }

    private String scalarFallback(String path, String profile) {
        var base = "sample-" + leafName(path);
        if (!OpenApiContractSmokeRunRequest.DEFAULT_PROFILE.equals(profile)) {
            return profile + "-" + base;
        }
        return base;
    }

    private String leafName(String path) {
        if (!StringUtils.hasText(path)) {
            return "value";
        }
        var normalized = path.replace('[', '.').replace("]", "");
        var parts = normalized.split("\\.");
        return parts[parts.length - 1].replaceAll("[^A-Za-z0-9_-]", "");
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> raw) {
        var map = new LinkedHashMap<String, Object>();
        raw.forEach((key, value) -> {
            if (key != null) {
                map.put(String.valueOf(key), value);
            }
        });
        return map;
    }
}
