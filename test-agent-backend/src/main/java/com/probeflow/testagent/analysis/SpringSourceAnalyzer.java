package com.probeflow.testagent.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.sourcematerial.SourceMaterial;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class SpringSourceAnalyzer {

    SpringSourceParseOutcome analyze(SourceMaterial material, Path sourceRoot) {
        var parser = new JavaParser(new ParserConfiguration());
        var operations = new ArrayList<ParsedSpringOperation>();
        var javaFiles = new ArrayList<ParsedJavaFile>();
        var errors = new ArrayList<String>();
        List<Path> sourceFiles;

        try (var paths = Files.walk(sourceRoot)) {
            sourceFiles = paths
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                .sorted()
                .toList();

            for (var javaFile : sourceFiles) {
                var parseResult = parser.parse(javaFile);
                if (!parseResult.getProblems().isEmpty()) {
                    errors.add(javaFile + ": " + parseResult.getProblems());
                }
                if (parseResult.getResult().isEmpty()) {
                    continue;
                }
                javaFiles.add(new ParsedJavaFile(javaFile, parseResult.getResult().orElseThrow()));
            }
        } catch (IOException exception) {
            return SpringSourceParseOutcome.failure("SPRING_SOURCE_NOT_READABLE", exception.getMessage());
        }

        var dtoIndex = dtoIndex(javaFiles);
        for (var javaFile : javaFiles) {
            operations.addAll(extractSpringOperations(material, sourceRoot, javaFile.path(), javaFile.compilationUnit(), dtoIndex));
        }

        if (operations.isEmpty()) {
            if (sourceFiles.isEmpty()) {
                return SpringSourceParseOutcome.failure(
                    "NO_APIS_FOUND",
                    "No Java source files were found in the local directory."
                );
            }
            return SpringSourceParseOutcome.failure(
                "NO_HTTP_APIS_FOUND",
                "Java source files were found, but no Spring HTTP routes were detected."
            );
        }

        return SpringSourceParseOutcome.success(systemNameForSource(material), operations, errors);
    }

    private List<ParsedSpringOperation> extractSpringOperations(
        SourceMaterial material,
        Path sourceRoot,
        Path javaFile,
        CompilationUnit compilationUnit,
        Map<String, DtoShape> dtoIndex
    ) {
        var operations = new ArrayList<ParsedSpringOperation>();
        for (var controller : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!hasAnnotation(controller.getAnnotations(), "RestController")) {
                continue;
            }

            var classPaths = mappingPaths(controller.getAnnotations(), "RequestMapping");
            var packageName = compilationUnit.getPackageDeclaration()
                .map(packageDeclaration -> packageDeclaration.getNameAsString())
                .orElse("");
            var moduleName = moduleName(packageName, controller.getNameAsString());

            for (var method : controller.getMethods()) {
                var mapping = methodMapping(method);
                if (mapping == null) {
                    continue;
                }

                for (var httpMethod : mapping.httpMethods()) {
                    for (var classPath : classPaths) {
                        for (var methodPath : mapping.paths()) {
                            operations.add(toSpringOperation(
                                material,
                                sourceRoot,
                                javaFile,
                                controller,
                                method,
                                httpMethod,
                                combinePaths(classPath, methodPath),
                                moduleName,
                                dtoIndex
                            ));
                        }
                    }
                }
            }
        }
        return operations;
    }

    private ParsedSpringOperation toSpringOperation(
        SourceMaterial material,
        Path sourceRoot,
        Path javaFile,
        ClassOrInterfaceDeclaration controller,
        MethodDeclaration method,
        HttpMethod httpMethod,
        String path,
        String moduleName,
        Map<String, DtoShape> dtoIndex
    ) {
        var sourceLocation = new LinkedHashMap<String, Object>();
        sourceLocation.put("materialId", material.getMaterialId());
        sourceLocation.put("filePath", javaFile.toString());
        sourceLocation.put("relativePath", sourceRoot.relativize(javaFile).toString().replace('\\', '/'));
        sourceLocation.put("className", controller.getNameAsString());
        sourceLocation.put("methodName", method.getNameAsString());
        method.getBegin().ifPresent(position -> sourceLocation.put("line", position.line));

        return new ParsedSpringOperation(
            httpMethod,
            path,
            moduleName,
            controller.getNameAsString() + "#" + method.getNameAsString(),
            null,
            controller.getNameAsString() + "#" + method.getNameAsString(),
            springParameters(method, dtoIndex),
            springConstraints(method, dtoIndex),
            springAuth(controller, method),
            sourceLocation,
            controller.getNameAsString(),
            method.getNameAsString()
        );
    }

    private boolean hasAnnotation(List<AnnotationExpr> annotations, String annotationName) {
        return annotations.stream().anyMatch(annotation -> annotationName(annotation).equals(annotationName));
    }

    private SpringMapping methodMapping(MethodDeclaration method) {
        for (var annotation : method.getAnnotations()) {
            var annotationName = annotationName(annotation);
            var methods = switch (annotationName) {
                case "GetMapping" -> List.of(HttpMethod.GET);
                case "PostMapping" -> List.of(HttpMethod.POST);
                case "PutMapping" -> List.of(HttpMethod.PUT);
                case "DeleteMapping" -> List.of(HttpMethod.DELETE);
                case "PatchMapping" -> List.of(HttpMethod.PATCH);
                case "RequestMapping" -> requestMappingMethods(annotation);
                default -> List.<HttpMethod>of();
            };
            if (!methods.isEmpty()) {
                return new SpringMapping(mappingPaths(annotation), methods);
            }
        }
        return null;
    }

    private List<HttpMethod> requestMappingMethods(AnnotationExpr annotation) {
        return annotationMember(annotation, "method")
            .map(this::httpMethods)
            .filter(methods -> !methods.isEmpty())
            .orElse(List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH));
    }

    private List<HttpMethod> httpMethods(Expression expression) {
        if (expression.isArrayInitializerExpr()) {
            return expression.asArrayInitializerExpr().getValues().stream()
                .map(this::httpMethod)
                .flatMap(Optional::stream)
                .toList();
        }
        return httpMethod(expression).map(List::of).orElse(List.of());
    }

    private Optional<HttpMethod> httpMethod(Expression expression) {
        var methodName = switch (expression) {
            case FieldAccessExpr fieldAccess -> fieldAccess.getNameAsString();
            case NameExpr name -> name.getNameAsString();
            default -> null;
        };
        if (methodName == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(HttpMethod.valueOf(methodName));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private List<String> mappingPaths(List<AnnotationExpr> annotations, String annotationName) {
        return annotations.stream()
            .filter(annotation -> annotationName(annotation).equals(annotationName))
            .findFirst()
            .map(this::mappingPaths)
            .orElse(List.of(""));
    }

    private List<String> mappingPaths(AnnotationExpr annotation) {
        return annotationMember(annotation, "path")
            .or(() -> annotationMember(annotation, "value"))
            .map(this::stringValues)
            .filter(values -> !values.isEmpty())
            .orElse(List.of(""));
    }

    private Optional<Expression> annotationMember(AnnotationExpr annotation, String memberName) {
        if (annotation instanceof SingleMemberAnnotationExpr singleMember && memberName.equals("value")) {
            return Optional.of(singleMember.getMemberValue());
        }
        if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            return normalAnnotation.getPairs().stream()
                .filter(pair -> pair.getNameAsString().equals(memberName))
                .findFirst()
                .map(MemberValuePair::getValue);
        }
        return Optional.empty();
    }

    private List<String> stringValues(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return List.of(expression.asStringLiteralExpr().asString());
        }
        if (expression instanceof ArrayInitializerExpr arrayInitializer) {
            return arrayInitializer.getValues().stream()
                .filter(Expression::isStringLiteralExpr)
                .map(value -> value.asStringLiteralExpr().asString())
                .toList();
        }
        return List.of();
    }

    private String annotationName(AnnotationExpr annotation) {
        var name = annotation.getNameAsString();
        var separator = name.lastIndexOf('.');
        return separator == -1 ? name : name.substring(separator + 1);
    }

    private Map<String, DtoShape> dtoIndex(List<ParsedJavaFile> javaFiles) {
        var dtoIndex = new LinkedHashMap<String, DtoShape>();
        for (var javaFile : javaFiles) {
            for (var classDeclaration : javaFile.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
                var fields = classDeclaration.getFields().stream()
                    .flatMap(field -> field.getVariables().stream().map(variable -> dtoFieldShape(field.getAnnotations(), variable.getNameAsString(), variable.getTypeAsString())))
                    .toList();
                if (!fields.isEmpty()) {
                    dtoIndex.put(classDeclaration.getNameAsString(), new DtoShape(classDeclaration.getNameAsString(), fields));
                }
            }
        }
        return dtoIndex;
    }

    private Map<String, Object> dtoFieldShape(List<AnnotationExpr> annotations, String name, String type) {
        var shape = new LinkedHashMap<String, Object>();
        var validations = validationHints(annotations);
        shape.put("name", name);
        shape.put("type", type);
        shape.put("required", hasRequiredValidation(annotations));
        if (!validations.isEmpty()) {
            shape.put("validations", validations);
        }
        return shape;
    }

    private Map<String, Object> springParameters(MethodDeclaration method, Map<String, DtoShape> dtoIndex) {
        var parameters = new LinkedHashMap<String, Object>();
        for (var parameter : method.getParameters()) {
            for (var annotation : parameter.getAnnotations()) {
                switch (annotationName(annotation)) {
                    case "PathVariable" -> parameterBucket(parameters, "path").add(parameterShape(annotation, parameter, true));
                    case "RequestParam" -> parameterBucket(parameters, "query").add(parameterShape(annotation, parameter, required(annotation, true)));
                    case "RequestHeader" -> parameterBucket(parameters, "header").add(parameterShape(annotation, parameter, required(annotation, true)));
                    case "RequestBody" -> parameters.put("requestBody", requestBodyShape(annotation, parameter, dtoIndex));
                    default -> {
                    }
                }
            }
        }
        responseBodyShape(method, dtoIndex).ifPresent(responseBody -> parameters.put("responseBody", responseBody));
        return parameters;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parameterBucket(Map<String, Object> parameters, String key) {
        return (List<Map<String, Object>>) parameters.computeIfAbsent(key, ignored -> new ArrayList<Map<String, Object>>());
    }

    private Map<String, Object> parameterShape(
        AnnotationExpr annotation,
        com.github.javaparser.ast.body.Parameter parameter,
        boolean required
    ) {
        var shape = new LinkedHashMap<String, Object>();
        shape.put("name", annotationStringValue(annotation, "name")
            .or(() -> annotationStringValue(annotation, "value"))
            .orElse(parameter.getNameAsString()));
        shape.put("type", parameter.getTypeAsString());
        shape.put("required", required);
        return shape;
    }

    private Map<String, Object> requestBodyShape(
        AnnotationExpr annotation,
        com.github.javaparser.ast.body.Parameter parameter,
        Map<String, DtoShape> dtoIndex
    ) {
        var body = new LinkedHashMap<String, Object>();
        var typeName = simpleTypeName(parameter.getTypeAsString());
        body.put("name", parameter.getNameAsString());
        body.put("type", typeName);
        body.put("required", required(annotation, true));
        var dto = dtoIndex.get(typeName);
        if (dto != null) {
            body.put("fields", dto.fields());
        }
        return body;
    }

    private Optional<Map<String, Object>> responseBodyShape(MethodDeclaration method, Map<String, DtoShape> dtoIndex) {
        var typeName = responseTypeName(method.getTypeAsString());
        if (typeName.isBlank() || "void".equals(typeName) || "Void".equals(typeName)) {
            return Optional.empty();
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("type", typeName);
        var dto = dtoIndex.get(typeName);
        if (dto != null) {
            body.put("fields", dto.fields());
        }
        return Optional.of(body);
    }

    private Map<String, Object> springConstraints(MethodDeclaration method, Map<String, DtoShape> dtoIndex) {
        var constraints = new LinkedHashMap<String, Object>();
        for (var parameter : method.getParameters()) {
            for (var annotation : parameter.getAnnotations()) {
                var annotationName = annotationName(annotation);
                if ("RequestBody".equals(annotationName)) {
                    addDtoConstraints("requestBody", simpleTypeName(parameter.getTypeAsString()), dtoIndex, constraints);
                } else if ("RequestParam".equals(annotationName)) {
                    addParameterConstraints("query." + parameterName(annotation, parameter), parameter.getAnnotations(), constraints);
                } else if ("RequestHeader".equals(annotationName)) {
                    addParameterConstraints("header." + parameterName(annotation, parameter), parameter.getAnnotations(), constraints);
                } else if ("PathVariable".equals(annotationName)) {
                    addParameterConstraints("path." + parameterName(annotation, parameter), parameter.getAnnotations(), constraints);
                }
            }
        }
        return constraints;
    }

    private void addDtoConstraints(
        String prefix,
        String typeName,
        Map<String, DtoShape> dtoIndex,
        Map<String, Object> constraints
    ) {
        var dto = dtoIndex.get(typeName);
        if (dto == null) {
            return;
        }
        for (var field : dto.fields()) {
            var fieldName = (String) field.get("name");
            if (Boolean.TRUE.equals(field.get("required"))) {
                requiredList(constraints).add(prefix + "." + fieldName);
            }
            @SuppressWarnings("unchecked")
            var validations = (Map<String, Object>) field.get("validations");
            if (validations != null && !validations.isEmpty()) {
                validationsMap(constraints).put(prefix + "." + fieldName, validations);
            }
        }
    }

    private void addParameterConstraints(
        String parameterPath,
        List<AnnotationExpr> annotations,
        Map<String, Object> constraints
    ) {
        if (hasRequiredValidation(annotations)) {
            requiredList(constraints).add(parameterPath);
        }
        var validations = validationHints(annotations);
        if (!validations.isEmpty()) {
            validationsMap(constraints).put(parameterPath, validations);
        }
    }

    private Map<String, Object> springAuth(ClassOrInterfaceDeclaration controller, MethodDeclaration method) {
        var annotations = new ArrayList<Map<String, Object>>();
        controller.getAnnotations().forEach(annotation -> addAuthAnnotation(annotation, annotations));
        method.getAnnotations().forEach(annotation -> addAuthAnnotation(annotation, annotations));

        var auth = new LinkedHashMap<String, Object>();
        auth.put("required", !annotations.isEmpty());
        if (!annotations.isEmpty()) {
            auth.put("annotations", annotations);
        }
        return auth;
    }

    private void addAuthAnnotation(AnnotationExpr annotation, List<Map<String, Object>> annotations) {
        var name = annotationName(annotation);
        if (!List.of("PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed").contains(name)) {
            return;
        }
        var authAnnotation = new LinkedHashMap<String, Object>();
        authAnnotation.put("name", name);
        annotationMember(annotation, "value")
            .map(this::annotationValue)
            .filter(value -> !value.isEmpty())
            .ifPresent(value -> authAnnotation.put("value", value));
        annotations.add(authAnnotation);
    }

    private Optional<String> annotationStringValue(AnnotationExpr annotation, String memberName) {
        return annotationMember(annotation, memberName)
            .filter(Expression::isStringLiteralExpr)
            .map(value -> value.asStringLiteralExpr().asString());
    }

    private boolean required(AnnotationExpr annotation, boolean defaultValue) {
        return annotationMember(annotation, "required")
            .filter(Expression::isBooleanLiteralExpr)
            .map(value -> value.asBooleanLiteralExpr().getValue())
            .orElse(defaultValue);
    }

    private String parameterName(AnnotationExpr annotation, com.github.javaparser.ast.body.Parameter parameter) {
        return annotationStringValue(annotation, "name")
            .or(() -> annotationStringValue(annotation, "value"))
            .orElse(parameter.getNameAsString());
    }

    private boolean hasRequiredValidation(List<AnnotationExpr> annotations) {
        return annotations.stream()
            .map(this::annotationName)
            .anyMatch(name -> List.of("NotNull", "NotBlank", "NotEmpty").contains(name));
    }

    private Map<String, Object> validationHints(List<AnnotationExpr> annotations) {
        var validations = new LinkedHashMap<String, Object>();
        for (var annotation : annotations) {
            switch (annotationName(annotation)) {
                case "Size" -> {
                    annotationIntegerValue(annotation, "min").ifPresent(value -> validations.put("minLength", value));
                    annotationIntegerValue(annotation, "max").ifPresent(value -> validations.put("maxLength", value));
                }
                case "Min" -> annotationIntegerValue(annotation, "value").ifPresent(value -> validations.put("minimum", value));
                case "Max" -> annotationIntegerValue(annotation, "value").ifPresent(value -> validations.put("maximum", value));
                case "Pattern" -> annotationStringValue(annotation, "regexp")
                    .or(() -> annotationStringValue(annotation, "value"))
                    .ifPresent(value -> validations.put("pattern", value));
                default -> {
                }
            }
        }
        return validations;
    }

    private Optional<Integer> annotationIntegerValue(AnnotationExpr annotation, String memberName) {
        return annotationMember(annotation, memberName).flatMap(this::integerValue);
    }

    private Optional<Integer> integerValue(Expression expression) {
        try {
            if (expression.isIntegerLiteralExpr()) {
                return Optional.of(Integer.parseInt(expression.asIntegerLiteralExpr().asNumber().toString()));
            }
            if (expression.isLongLiteralExpr()) {
                return Optional.of(Integer.parseInt(expression.asLongLiteralExpr().asNumber().toString()));
            }
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private String annotationValue(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return expression.asStringLiteralExpr().asString();
        }
        if (expression instanceof ArrayInitializerExpr arrayInitializer) {
            return arrayInitializer.getValues().stream()
                .map(this::annotationValue)
                .filter(value -> !value.isBlank())
                .toList()
                .toString();
        }
        return expression.toString();
    }

    private String responseTypeName(String rawType) {
        var typeName = rawType.trim();
        if (typeName.startsWith("ResponseEntity<") && typeName.endsWith(">")) {
            return simpleTypeName(typeName.substring("ResponseEntity<".length(), typeName.length() - 1));
        }
        return simpleTypeName(typeName);
    }

    private String simpleTypeName(String rawType) {
        var typeName = rawType.trim();
        var genericStart = typeName.indexOf('<');
        if (genericStart > 0) {
            typeName = typeName.substring(0, genericStart);
        }
        var packageSeparator = typeName.lastIndexOf('.');
        return packageSeparator == -1 ? typeName : typeName.substring(packageSeparator + 1);
    }

    private String combinePaths(String classPath, String methodPath) {
        if ((classPath == null || classPath.isBlank()) && (methodPath == null || methodPath.isBlank())) {
            return "/";
        }
        return normalizePath((classPath == null ? "" : classPath) + "/" + (methodPath == null ? "" : methodPath));
    }

    private String moduleName(String packageName, String className) {
        if (packageName == null || packageName.isBlank()) {
            return className;
        }
        var separator = packageName.lastIndexOf('.');
        return separator == -1 ? packageName : packageName.substring(separator + 1);
    }

    private String systemNameForSource(SourceMaterial material) {
        var path = Path.of(material.getStoragePath()).getFileName();
        if (path != null && !path.toString().isBlank()) {
            return path.toString();
        }
        return "source-system";
    }

    @SuppressWarnings("unchecked")
    private List<String> requiredList(Map<String, Object> constraints) {
        return (List<String>) constraints.computeIfAbsent("required", ignored -> new ArrayList<String>());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validationsMap(Map<String, Object> constraints) {
        return (Map<String, Object>) constraints.computeIfAbsent("validations", ignored -> new LinkedHashMap<String, Object>());
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
}

record SpringSourceParseOutcome(
    boolean succeeded,
    String systemName,
    List<ParsedSpringOperation> operations,
    List<String> warnings,
    String errorCode,
    String errorMessage
) {

    static SpringSourceParseOutcome success(String systemName, List<ParsedSpringOperation> operations, List<String> warnings) {
        return new SpringSourceParseOutcome(true, systemName, List.copyOf(operations), List.copyOf(warnings), null, null);
    }

    static SpringSourceParseOutcome failure(String errorCode, String errorMessage) {
        return new SpringSourceParseOutcome(false, null, List.of(), List.of(), errorCode, errorMessage);
    }
}

record ParsedSpringOperation(
    HttpMethod httpMethod,
    String path,
    String moduleName,
    String summary,
    String description,
    String operationId,
    Map<String, Object> parameters,
    Map<String, Object> constraints,
    Map<String, Object> auth,
    Map<String, Object> sourceLocation,
    String className,
    String methodName
) {
}

record SpringMapping(List<String> paths, List<HttpMethod> httpMethods) {
}

record ParsedJavaFile(Path path, CompilationUnit compilationUnit) {
}

record DtoShape(String type, List<Map<String, Object>> fields) {
}
