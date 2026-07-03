package com.probeflow.testagent.analysis;

import com.probeflow.testagent.apispec.HttpMethod;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class OpenApiSourceAnalyzer {

    OpenApiParseOutcome analyze(String storagePath) {
        var options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);

        var parseResult = new OpenAPIV3Parser().readLocation(storagePath, null, options);
        var openApi = parseResult.getOpenAPI();
        if (openApi == null) {
            var message = parseResult.getMessages() == null || parseResult.getMessages().isEmpty()
                ? "OpenAPI document could not be parsed"
                : String.join("; ", parseResult.getMessages());
            return OpenApiParseOutcome.failure("OPENAPI_PARSE_FAILED", message);
        }

        var operations = new ArrayList<ParsedOpenApiOperation>();
        if (openApi.getPaths() != null) {
            openApi.getPaths().forEach((path, pathItem) -> {
                if (pathItem != null) {
                    pathItem.readOperationsMap().forEach((method, operation) -> {
                        var httpMethod = toHttpMethod(method);
                        if (httpMethod != null && operation != null) {
                            operations.add(toParsedOperation(openApi, normalizePath(path), httpMethod, operation));
                        }
                    });
                }
            });
        }

        return OpenApiParseOutcome.success(systemName(openApi), operations);
    }

    private ParsedOpenApiOperation toParsedOperation(
        OpenAPI openApi,
        String path,
        HttpMethod method,
        Operation operation
    ) {
        var parameters = new LinkedHashMap<String, Object>();
        var constraints = new LinkedHashMap<String, Object>();

        groupParameters(operation.getParameters(), parameters, constraints);
        putRequestBody(operation, parameters, constraints);
        putResponses(operation, parameters, constraints);

        var auth = authHints(openApi, operation);
        var module = operation.getTags() == null || operation.getTags().isEmpty() ? "default" : operation.getTags().getFirst();
        return new ParsedOpenApiOperation(
            method,
            path,
            module,
            operation.getSummary(),
            operation.getDescription(),
            operation.getOperationId(),
            parameters,
            constraints,
            auth
        );
    }

    private HttpMethod toHttpMethod(PathItem.HttpMethod method) {
        return switch (method) {
            case GET -> HttpMethod.GET;
            case POST -> HttpMethod.POST;
            case PUT -> HttpMethod.PUT;
            case DELETE -> HttpMethod.DELETE;
            case PATCH -> HttpMethod.PATCH;
            case HEAD, OPTIONS, TRACE -> null;
        };
    }

    private String systemName(OpenAPI openApi) {
        if (openApi.getInfo() != null && openApi.getInfo().getTitle() != null && !openApi.getInfo().getTitle().isBlank()) {
            return openApi.getInfo().getTitle();
        }
        return "default-system";
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        var normalized = path.trim().replaceAll("/{2,}", "/");
        normalized = normalized.startsWith("/") ? normalized : "/" + normalized;
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void groupParameters(
        List<Parameter> openApiParameters,
        Map<String, Object> parameters,
        Map<String, Object> constraints
    ) {
        if (openApiParameters == null) {
            return;
        }

        for (var parameter : openApiParameters) {
            if (parameter == null || parameter.getIn() == null || parameter.getName() == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            var bucket = (List<Map<String, Object>>) parameters.computeIfAbsent(parameter.getIn(), ignored -> new ArrayList<Map<String, Object>>());
            var parameterShape = new LinkedHashMap<String, Object>();
            parameterShape.put("name", parameter.getName());
            parameterShape.put("required", Boolean.TRUE.equals(parameter.getRequired()));
            parameterShape.put("schema", schemaShape(parameter.getSchema()));
            bucket.add(parameterShape);

            if (Boolean.TRUE.equals(parameter.getRequired())) {
                requiredList(constraints).add(parameter.getIn() + "." + parameter.getName());
            }
            collectSchemaConstraints(parameter.getName(), parameter.getSchema(), constraints);
        }
    }

    private void putRequestBody(
        Operation operation,
        Map<String, Object> parameters,
        Map<String, Object> constraints
    ) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return;
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("required", Boolean.TRUE.equals(operation.getRequestBody().getRequired()));
        body.put("content", contentShape(operation.getRequestBody().getContent().values()));
        parameters.put("requestBody", body);
        operation.getRequestBody().getContent().values().stream()
            .filter(Objects::nonNull)
            .map(mediaType -> mediaType.getSchema())
            .forEach(schema -> collectSchemaConstraints("requestBody", schema, constraints));
    }

    private void putResponses(
        Operation operation,
        Map<String, Object> parameters,
        Map<String, Object> constraints
    ) {
        if (operation.getResponses() == null) {
            return;
        }

        var responses = new LinkedHashMap<String, Object>();
        operation.getResponses().forEach((status, response) -> {
            var responseShape = new LinkedHashMap<String, Object>();
            responseShape.put("description", response.getDescription());
            if (response.getContent() != null) {
                responseShape.put("content", contentShape(response.getContent().values()));
                response.getContent().values().stream()
                    .filter(Objects::nonNull)
                    .map(mediaType -> mediaType.getSchema())
                    .forEach(schema -> collectSchemaConstraints("response." + status, schema, constraints));
            }
            responses.put(status, responseShape);
        });
        parameters.put("responses", responses);
    }

    private List<Map<String, Object>> contentShape(Collection<io.swagger.v3.oas.models.media.MediaType> mediaTypes) {
        return mediaTypes.stream()
            .filter(Objects::nonNull)
            .<Map<String, Object>>map(mediaType -> {
                var shape = new LinkedHashMap<String, Object>();
                shape.put("schema", schemaShape(mediaType.getSchema()));
                return shape;
            })
            .toList();
    }

    private Map<String, Object> schemaShape(Schema<?> schema) {
        var shape = new LinkedHashMap<String, Object>();
        if (schema == null) {
            return shape;
        }
        if (schema.getName() != null) {
            shape.put("name", schema.getName());
        }
        if (schema.get$ref() != null) {
            shape.put("ref", schema.get$ref());
        }
        if (schema.getType() != null) {
            shape.put("type", schema.getType());
        }
        if (schema.getFormat() != null) {
            shape.put("format", schema.getFormat());
        }
        if (schema.getDescription() != null) {
            shape.put("description", schema.getDescription());
        }
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            shape.put("enum", schema.getEnum());
        }
        if (schema.getRequired() != null && !schema.getRequired().isEmpty()) {
            shape.put("required", schema.getRequired());
        }
        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            var properties = new LinkedHashMap<String, Object>();
            schema.getProperties().forEach((name, propertySchema) -> properties.put(name, schemaShape((Schema<?>) propertySchema)));
            shape.put("properties", properties);
        }
        return shape;
    }

    private void collectSchemaConstraints(String prefix, Schema<?> schema, Map<String, Object> constraints) {
        if (schema == null) {
            return;
        }
        if (schema.getRequired() != null && !schema.getRequired().isEmpty()) {
            schema.getRequired().forEach(field -> requiredList(constraints).add(prefix + "." + field));
        }
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            enumsMap(constraints).put(prefix, schema.getEnum());
        }
        if (schema.getMinimum() != null || schema.getMaximum() != null || schema.getMinLength() != null || schema.getMaxLength() != null || schema.getPattern() != null) {
            var validation = new LinkedHashMap<String, Object>();
            putIfPresent(validation, "minimum", schema.getMinimum());
            putIfPresent(validation, "maximum", schema.getMaximum());
            putIfPresent(validation, "minLength", schema.getMinLength());
            putIfPresent(validation, "maxLength", schema.getMaxLength());
            putIfPresent(validation, "pattern", schema.getPattern());
            validationsMap(constraints).put(prefix, validation);
        }
        if (schema.getProperties() != null) {
            schema.getProperties().forEach((name, propertySchema) -> collectSchemaConstraints(prefix + "." + name, (Schema<?>) propertySchema, constraints));
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> requiredList(Map<String, Object> constraints) {
        return (List<String>) constraints.computeIfAbsent("required", ignored -> new ArrayList<String>());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> enumsMap(Map<String, Object> constraints) {
        return (Map<String, Object>) constraints.computeIfAbsent("enums", ignored -> new LinkedHashMap<String, Object>());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validationsMap(Map<String, Object> constraints) {
        return (Map<String, Object>) constraints.computeIfAbsent("validations", ignored -> new LinkedHashMap<String, Object>());
    }

    private Map<String, Object> authHints(OpenAPI openApi, Operation operation) {
        var requirements = operation.getSecurity();
        if (requirements == null) {
            requirements = openApi.getSecurity();
        }

        var auth = new LinkedHashMap<String, Object>();
        auth.put("required", requirements != null && !requirements.isEmpty());
        if (requirements != null && !requirements.isEmpty()) {
            auth.put("schemes", requirements.stream()
                .flatMap(requirement -> requirement.keySet().stream())
                .distinct()
                .toList());
            auth.put("requirements", requirements.stream().map(SecurityRequirement::keySet).map(List::copyOf).toList());
        }
        return auth;
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}

record OpenApiParseOutcome(
    boolean succeeded,
    String systemName,
    List<ParsedOpenApiOperation> operations,
    String errorCode,
    String errorMessage
) {

    static OpenApiParseOutcome success(String systemName, List<ParsedOpenApiOperation> operations) {
        return new OpenApiParseOutcome(true, systemName, List.copyOf(operations), null, null);
    }

    static OpenApiParseOutcome failure(String errorCode, String errorMessage) {
        return new OpenApiParseOutcome(false, null, List.of(), errorCode, errorMessage);
    }
}

record ParsedOpenApiOperation(
    HttpMethod httpMethod,
    String path,
    String moduleName,
    String summary,
    String description,
    String operationId,
    Map<String, Object> parameters,
    Map<String, Object> constraints,
    Map<String, Object> auth
) {
}
