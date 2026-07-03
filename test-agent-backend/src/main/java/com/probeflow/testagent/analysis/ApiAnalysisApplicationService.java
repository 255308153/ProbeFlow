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
import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.sourcematerial.IngestStatus;
import com.probeflow.testagent.sourcematerial.MaterialType;
import com.probeflow.testagent.sourcematerial.SourceMaterial;
import com.probeflow.testagent.sourcematerial.SourceMaterialRepository;
import com.probeflow.testagent.task.PlanStep;
import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.PlanStepType;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiAnalysisApplicationService {

    private final SourceMaterialRepository sourceMaterials;
    private final ApiSpecRepository apiSpecs;
    private final TaskRepository tasks;
    private final PlanStepRepository planSteps;

    public ApiAnalysisApplicationService(
        SourceMaterialRepository sourceMaterials,
        ApiSpecRepository apiSpecs,
        TaskRepository tasks,
        PlanStepRepository planSteps
    ) {
        this.sourceMaterials = sourceMaterials;
        this.apiSpecs = apiSpecs;
        this.tasks = tasks;
        this.planSteps = planSteps;
    }

    @Transactional
    public ApiAnalysisResult analyze(ApiAnalysisRequest request) {
        var material = resolveMaterial(request);
        material.setIngestStatus(IngestStatus.PENDING);
        material = sourceMaterials.save(material);

        var task = createTask(material, request.requestedBy());
        material.setTaskId(task.getTaskId());
        sourceMaterials.save(material);

        var validationStep = createStep(task.getTaskId(), 1, "Validate source material", material.getStoragePath());
        var routeStep = createStep(task.getTaskId(), 2, "Route API analysis parser", material.getMaterialType().name());

        var validation = validateMaterial(material);
        if (!validation.valid()) {
            failStep(validationStep, validation.errorCode(), validation.errorMessage());
            skipStep(routeStep, "Validation failed");
            return failAnalysis(material, task, null, validation.errorCode(), validation.errorMessage());
        }
        succeedStep(validationStep, "Material is readable");

        var route = routeMaterial(material.getMaterialType());
        if (route == null) {
            failStep(routeStep, "UNSUPPORTED_MATERIAL_TYPE", "Unsupported material type: " + material.getMaterialType());
            return failAnalysis(
                material,
                task,
                null,
                "UNSUPPORTED_MATERIAL_TYPE",
                "Unsupported material type: " + material.getMaterialType()
            );
        }

        succeedStep(routeStep, "Parser route: " + route);
        if (material.getMaterialType() == MaterialType.OPENAPI_FILE) {
            return analyzeOpenApi(material, task, route);
        }
        if (material.getMaterialType() == MaterialType.SWAGGER_FILE) {
            return rejectUnsupportedSwagger(material, task, route);
        }
        if (material.getMaterialType() == MaterialType.SOURCE_DIRECTORY) {
            return analyzeSpringSource(material, task, route);
        }

        task.setStatus(TaskStatus.COMPLETED);
        task.setMetadata(Map.of("parserRoute", route, "apiSpecCount", 0));
        task.setTargetApiSpecIds(List.of());
        tasks.save(task);

        material.setIngestStatus(IngestStatus.READY);
        sourceMaterials.save(material);

        return ApiAnalysisResult.success(material.getMaterialId(), task.getTaskId(), route, List.of());
    }

    private ApiAnalysisResult rejectUnsupportedSwagger(SourceMaterial material, Task task, String route) {
        var swaggerStep = createStep(task.getTaskId(), 3, "Reject unsupported Swagger input", material.getStoragePath());
        var message = "Swagger 2.x input is not supported by API analysis yet";
        failStep(swaggerStep, "SWAGGER_UNSUPPORTED", message);
        return failAnalysis(material, task, route, "SWAGGER_UNSUPPORTED", message);
    }

    private ApiAnalysisResult analyzeSpringSource(SourceMaterial material, Task task, String route) {
        var parseStep = createStep(task.getTaskId(), 3, "Parse Spring controller source", material.getStoragePath());
        var mergeStep = createStep(task.getTaskId(), 4, "Persist ApiSpec operations", material.getMaterialId());

        var parseResult = parseSpringSource(material);
        if (!parseResult.succeeded()) {
            failStep(parseStep, parseResult.errorCode(), parseResult.errorMessage());
            skipStep(mergeStep, "Spring source parse failed");
            return failAnalysis(material, task, route, parseResult.errorCode(), parseResult.errorMessage());
        }
        succeedStep(parseStep, "Parsed Spring routes: " + parseResult.operations().size());

        var savedSpecs = new ArrayList<ApiSpec>();
        var latestRouteKeys = new LinkedHashSet<String>();
        for (var operation : parseResult.operations()) {
            latestRouteKeys.add(routeKey(operation.httpMethod(), operation.path()));
            savedSpecs.add(upsertSpringApiSpec(material, parseResult.systemName(), operation));
        }
        markRoutesAbsentFromLatestAnalysis(material.getMaterialId(), latestRouteKeys);
        var apiSpecIds = savedSpecs.stream().map(ApiSpec::getApiSpecId).toList();
        succeedStep(mergeStep, "Persisted ApiSpec operations: " + apiSpecIds.size());

        task.setStatus(TaskStatus.COMPLETED);
        task.setTargetApiSpecIds(apiSpecIds);
        task.setMetadata(Map.of("parserRoute", route, "apiSpecCount", apiSpecIds.size()));
        tasks.save(task);

        material.setIngestStatus(IngestStatus.READY);
        sourceMaterials.save(material);

        return ApiAnalysisResult.success(material.getMaterialId(), task.getTaskId(), route, apiSpecIds);
    }

    private ApiAnalysisResult analyzeOpenApi(SourceMaterial material, Task task, String route) {
        var parseStep = createStep(task.getTaskId(), 3, "Parse OpenAPI document", material.getStoragePath());
        var mergeStep = createStep(task.getTaskId(), 4, "Persist ApiSpec operations", material.getMaterialId());

        var parseResult = parseOpenApi(material.getStoragePath());
        if (!parseResult.succeeded()) {
            failStep(parseStep, parseResult.errorCode(), parseResult.errorMessage());
            skipStep(mergeStep, "OpenAPI parse failed");
            return failAnalysis(material, task, route, parseResult.errorCode(), parseResult.errorMessage());
        }
        succeedStep(parseStep, "Parsed OpenAPI operations: " + parseResult.operations().size());

        var savedSpecs = new ArrayList<ApiSpec>();
        var latestRouteKeys = new LinkedHashSet<String>();
        for (var operation : parseResult.operations()) {
            latestRouteKeys.add(routeKey(operation.httpMethod(), operation.path()));
            savedSpecs.add(upsertApiSpec(material, parseResult.systemName(), operation));
        }
        markRoutesAbsentFromLatestAnalysis(material.getMaterialId(), latestRouteKeys);
        var apiSpecIds = savedSpecs.stream().map(ApiSpec::getApiSpecId).toList();
        succeedStep(mergeStep, "Persisted ApiSpec operations: " + apiSpecIds.size());

        task.setStatus(TaskStatus.COMPLETED);
        task.setTargetApiSpecIds(apiSpecIds);
        task.setMetadata(Map.of("parserRoute", route, "apiSpecCount", apiSpecIds.size()));
        tasks.save(task);

        material.setIngestStatus(IngestStatus.READY);
        sourceMaterials.save(material);

        return ApiAnalysisResult.success(material.getMaterialId(), task.getTaskId(), route, apiSpecIds);
    }

    private ApiSpec upsertApiSpec(SourceMaterial material, String systemName, ParsedOpenApiOperation operation) {
        var existing = findExistingApiSpec(material.getMaterialId(), operation);
        if (existing == null) {
            return apiSpecs.save(toApiSpec(material, systemName, operation));
        }

        var changed = applyApiSpecChanges(existing, material, systemName, operation);
        if (changed) {
            existing.setVersion(existing.getVersion() + 1);
            return apiSpecs.save(existing);
        }
        if (!existing.isPresentInLatestAnalysis()) {
            existing.setPresentInLatestAnalysis(true);
            return apiSpecs.save(existing);
        }
        return existing;
    }

    private ApiSpec upsertSpringApiSpec(SourceMaterial material, String systemName, ParsedSpringOperation operation) {
        var existing = findExistingApiSpec(
            material.getMaterialId(),
            operation.operationId(),
            operation.httpMethod(),
            operation.path()
        );
        if (existing == null) {
            return apiSpecs.save(toSpringApiSpec(material, systemName, operation));
        }

        var changed = applySpringApiSpecChanges(existing, material, systemName, operation);
        if (changed) {
            existing.setVersion(existing.getVersion() + 1);
            return apiSpecs.save(existing);
        }
        if (!existing.isPresentInLatestAnalysis()) {
            existing.setPresentInLatestAnalysis(true);
            return apiSpecs.save(existing);
        }
        return existing;
    }

    private ApiSpec findExistingApiSpec(String materialId, ParsedOpenApiOperation operation) {
        return findExistingApiSpec(materialId, operation.operationId(), operation.httpMethod(), operation.path());
    }

    private ApiSpec findExistingApiSpec(String materialId, String operationId, HttpMethod httpMethod, String path) {
        if (operationId != null && !operationId.isBlank()) {
            var byOperationId = apiSpecs.findFirstBySourceMaterialIdAndOperationId(materialId, operationId);
            if (byOperationId.isPresent()) {
                return byOperationId.get();
            }
        }
        return apiSpecs.findFirstBySourceMaterialIdAndHttpMethodAndPath(materialId, httpMethod, path)
            .orElse(null);
    }

    private boolean applyApiSpecChanges(
        ApiSpec apiSpec,
        SourceMaterial material,
        String systemName,
        ParsedOpenApiOperation operation
    ) {
        var changed = false;
        changed |= setIfChanged(apiSpec.getSystemName(), systemName, apiSpec::setSystemName);
        changed |= setIfChanged(apiSpec.getModuleName(), operation.moduleName(), apiSpec::setModuleName);
        changed |= setIfChanged(apiSpec.getHttpMethod(), operation.httpMethod(), apiSpec::setHttpMethod);
        changed |= setIfChanged(apiSpec.getPath(), operation.path(), apiSpec::setPath);
        changed |= setIfChanged(apiSpec.getSummary(), operation.summary(), apiSpec::setSummary);
        changed |= setIfChanged(apiSpec.getDescription(), operation.description(), apiSpec::setDescription);
        changed |= setIfChanged(apiSpec.getOperationId(), operation.operationId(), apiSpec::setOperationId);
        changed |= setIfChanged(apiSpec.getParameters(), operation.parameters(), apiSpec::setParameters);
        changed |= setIfChanged(apiSpec.getConstraints(), operation.constraints(), apiSpec::setConstraints);
        changed |= setIfChanged(apiSpec.getAuth(), operation.auth(), apiSpec::setAuth);
        changed |= setIfChanged(apiSpec.getSourceRef(), sourceRef(material, operation), apiSpec::setSourceRef);
        changed |= setIfChanged(apiSpec.getSourceLocation(), sourceLocation(material, operation), apiSpec::setSourceLocation);
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceMaterialId(material.getMaterialId());
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(false);
        apiSpec.setPresentInLatestAnalysis(true);
        return changed;
    }

    private boolean applySpringApiSpecChanges(
        ApiSpec apiSpec,
        SourceMaterial material,
        String systemName,
        ParsedSpringOperation operation
    ) {
        var changed = false;
        changed |= setIfChanged(apiSpec.getSystemName(), systemName, apiSpec::setSystemName);
        changed |= setIfChanged(apiSpec.getModuleName(), operation.moduleName(), apiSpec::setModuleName);
        changed |= setIfChanged(apiSpec.getHttpMethod(), operation.httpMethod(), apiSpec::setHttpMethod);
        changed |= setIfChanged(apiSpec.getPath(), operation.path(), apiSpec::setPath);
        changed |= setIfChanged(apiSpec.getSummary(), operation.summary(), apiSpec::setSummary);
        changed |= setIfChanged(apiSpec.getDescription(), operation.description(), apiSpec::setDescription);
        changed |= setIfChanged(apiSpec.getOperationId(), operation.operationId(), apiSpec::setOperationId);
        changed |= setIfChanged(apiSpec.getParameters(), operation.parameters(), apiSpec::setParameters);
        changed |= setIfChanged(apiSpec.getConstraints(), operation.constraints(), apiSpec::setConstraints);
        changed |= setIfChanged(apiSpec.getAuth(), operation.auth(), apiSpec::setAuth);
        changed |= setIfChanged(apiSpec.getSourceRef(), sourceRef(material, operation), apiSpec::setSourceRef);
        changed |= setIfChanged(apiSpec.getSourceLocation(), operation.sourceLocation(), apiSpec::setSourceLocation);
        apiSpec.setSourceType(ApiSpecSourceType.CODE_ANALYSIS);
        apiSpec.setSourceMaterialId(material.getMaterialId());
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(false);
        apiSpec.setValidationReady(false);
        apiSpec.setAuthReady(false);
        apiSpec.setKnowledgeContextReady(false);
        apiSpec.setPresentInLatestAnalysis(true);
        return changed;
    }

    private <T> boolean setIfChanged(T currentValue, T newValue, java.util.function.Consumer<T> setter) {
        if (Objects.equals(currentValue, newValue)) {
            return false;
        }
        setter.accept(newValue);
        return true;
    }

    private void markRoutesAbsentFromLatestAnalysis(String materialId, LinkedHashSet<String> latestRouteKeys) {
        apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(materialId).stream()
            .filter(apiSpec -> !latestRouteKeys.contains(routeKey(apiSpec.getHttpMethod(), apiSpec.getPath())))
            .filter(ApiSpec::isPresentInLatestAnalysis)
            .forEach(apiSpec -> {
                apiSpec.setPresentInLatestAnalysis(false);
                apiSpecs.save(apiSpec);
            });
    }

    private OpenApiParseOutcome parseOpenApi(String storagePath) {
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

    private SpringSourceParseOutcome parseSpringSource(SourceMaterial material) {
        var sourceRoot = Path.of(material.getStoragePath());
        var parser = new JavaParser(new ParserConfiguration());
        var operations = new ArrayList<ParsedSpringOperation>();
        var errors = new ArrayList<String>();

        try (var paths = Files.walk(sourceRoot)) {
            var javaFiles = paths
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                .sorted()
                .toList();

            for (var javaFile : javaFiles) {
                var parseResult = parser.parse(javaFile);
                if (parseResult.getResult().isEmpty()) {
                    errors.add(javaFile + ": " + parseResult.getProblems());
                    continue;
                }
                operations.addAll(extractSpringOperations(material, sourceRoot, javaFile, parseResult.getResult().orElseThrow()));
            }
        } catch (IOException exception) {
            return SpringSourceParseOutcome.failure("SPRING_SOURCE_NOT_READABLE", exception.getMessage());
        }

        if (operations.isEmpty()) {
            var message = errors.isEmpty()
                ? "No Spring @RestController routes were found"
                : "No Spring @RestController routes were found; parse errors: " + String.join("; ", errors);
            return SpringSourceParseOutcome.failure("NO_APIS_FOUND", message);
        }

        return SpringSourceParseOutcome.success(systemNameForSource(material), operations, errors);
    }

    private List<ParsedSpringOperation> extractSpringOperations(
        SourceMaterial material,
        Path sourceRoot,
        Path javaFile,
        CompilationUnit compilationUnit
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
                                moduleName
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
        String moduleName
    ) {
        var sourceLocation = new LinkedHashMap<String, Object>();
        sourceLocation.put("materialId", material.getMaterialId());
        sourceLocation.put("filePath", javaFile.toString());
        sourceLocation.put("relativePath", sourceRoot.relativize(javaFile).toString());
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
            springParameters(method),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            sourceLocation,
            controller.getNameAsString(),
            method.getNameAsString()
        );
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

    private ApiSpec toApiSpec(SourceMaterial material, String systemName, ParsedOpenApiOperation operation) {
        var apiSpec = new ApiSpec();
        apiSpec.setSystemName(systemName);
        apiSpec.setModuleName(operation.moduleName());
        apiSpec.setHttpMethod(operation.httpMethod());
        apiSpec.setPath(operation.path());
        apiSpec.setSummary(operation.summary());
        apiSpec.setDescription(operation.description());
        apiSpec.setOperationId(operation.operationId());
        apiSpec.setParameters(operation.parameters());
        apiSpec.setConstraints(operation.constraints());
        apiSpec.setAuth(operation.auth());
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceMaterialId(material.getMaterialId());
        apiSpec.setSourceRef(sourceRef(material, operation));
        apiSpec.setSourceLocation(sourceLocation(material, operation));
        apiSpec.setVersion(1);
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(false);
        apiSpec.setPresentInLatestAnalysis(true);
        return apiSpec;
    }

    private ApiSpec toSpringApiSpec(SourceMaterial material, String systemName, ParsedSpringOperation operation) {
        var apiSpec = new ApiSpec();
        apiSpec.setSystemName(systemName);
        apiSpec.setModuleName(operation.moduleName());
        apiSpec.setHttpMethod(operation.httpMethod());
        apiSpec.setPath(operation.path());
        apiSpec.setSummary(operation.summary());
        apiSpec.setDescription(operation.description());
        apiSpec.setOperationId(operation.operationId());
        apiSpec.setParameters(operation.parameters());
        apiSpec.setConstraints(operation.constraints());
        apiSpec.setAuth(operation.auth());
        apiSpec.setSourceType(ApiSpecSourceType.CODE_ANALYSIS);
        apiSpec.setSourceMaterialId(material.getMaterialId());
        apiSpec.setSourceRef(sourceRef(material, operation));
        apiSpec.setSourceLocation(operation.sourceLocation());
        apiSpec.setVersion(1);
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(false);
        apiSpec.setValidationReady(false);
        apiSpec.setAuthReady(false);
        apiSpec.setKnowledgeContextReady(false);
        apiSpec.setPresentInLatestAnalysis(true);
        return apiSpec;
    }

    private String sourceRef(SourceMaterial material, ParsedOpenApiOperation operation) {
        return "openapi://" + material.getMaterialId() + "#/paths/" + jsonPointerPath(operation.path()) + "/" + operation.httpMethod().name().toLowerCase();
    }

    private String sourceRef(SourceMaterial material, ParsedSpringOperation operation) {
        return "spring-source://" + material.getMaterialId()
            + "#" + operation.className() + "#" + operation.methodName()
            + "[" + operation.httpMethod().name() + " " + operation.path() + "]";
    }

    private Map<String, Object> sourceLocation(SourceMaterial material, ParsedOpenApiOperation operation) {
        return Map.of(
            "materialId", material.getMaterialId(),
            "path", operation.path(),
            "method", operation.httpMethod().name()
        );
    }

    private String routeKey(HttpMethod httpMethod, String path) {
        return httpMethod.name() + " " + normalizePath(path);
    }

    private SourceMaterial resolveMaterial(ApiAnalysisRequest request) {
        if (request.materialId() != null && !request.materialId().isBlank()) {
            return sourceMaterials.findById(request.materialId())
                .orElseThrow(() -> new IllegalArgumentException("SourceMaterial not found: " + request.materialId()));
        }

        var material = new SourceMaterial();
        material.setMaterialType(request.materialType());
        material.setOriginalName(request.originalName());
        material.setOriginalRef(request.originalRef());
        material.setStoragePath(request.storagePath());
        return material;
    }

    private Task createTask(SourceMaterial material, String requestedBy) {
        var task = new Task();
        task.setTaskType(TaskType.API_ANALYSIS);
        task.setTaskName("Analyze API source material");
        task.setStatus(TaskStatus.ANALYZING);
        task.setSourceType(toTaskSourceType(material.getMaterialType()));
        task.setSourceRef(material.getMaterialId());
        task.setTargetApiSpecIds(new ArrayList<>());
        task.setPromotionMode(PromotionMode.AUTO);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator(requestedBy);
        task.setMetadata(new LinkedHashMap<>(Map.of(
            "materialType", material.getMaterialType().name(),
            "storagePath", material.getStoragePath()
        )));
        return tasks.save(task);
    }

    private TaskSourceType toTaskSourceType(MaterialType materialType) {
        return switch (materialType) {
            case OPENAPI_FILE, SWAGGER_FILE -> TaskSourceType.OPENAPI;
            case CODE_ARCHIVE, SOURCE_DIRECTORY, GIT_REPO -> TaskSourceType.CODE_REPO;
            case REQUIREMENT_DOC -> TaskSourceType.REQUIREMENT_DOC;
            case MANUAL_SELECTION -> TaskSourceType.MANUAL;
        };
    }

    private PlanStep createStep(String taskId, int order, String goal, String inputRef) {
        var step = new PlanStep();
        step.setTaskId(taskId);
        step.setStepType(PlanStepType.ANALYZE_CODE_API);
        step.setStepStatus(PlanStepStatus.RUNNING);
        step.setStepOrder(order);
        step.setGoal(goal);
        step.setInputRef(inputRef);
        step.setStartedAt(Instant.now());
        return planSteps.save(step);
    }

    private MaterialValidation validateMaterial(SourceMaterial material) {
        if (material.getMaterialType() == null) {
            return MaterialValidation.invalid("MATERIAL_TYPE_REQUIRED", "SourceMaterial material type is required");
        }

        var rawPath = material.getStoragePath();
        if (rawPath == null || rawPath.isBlank()) {
            return MaterialValidation.invalid("MATERIAL_PATH_REQUIRED", "SourceMaterial storage path is required");
        }

        var path = Path.of(rawPath);
        if (material.getMaterialType() == MaterialType.SOURCE_DIRECTORY || material.getMaterialType() == MaterialType.GIT_REPO) {
            if (!Files.isDirectory(path) || !Files.isReadable(path)) {
                return MaterialValidation.invalid("MATERIAL_NOT_READABLE", "SourceMaterial directory is not readable: " + rawPath);
            }
            return MaterialValidation.ok();
        }

        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return MaterialValidation.invalid("MATERIAL_NOT_READABLE", "SourceMaterial file is not readable: " + rawPath);
        }

        return MaterialValidation.ok();
    }

    private String routeMaterial(MaterialType materialType) {
        return switch (materialType) {
            case OPENAPI_FILE -> "openapi";
            case SWAGGER_FILE -> "swagger";
            case SOURCE_DIRECTORY -> "spring-source";
            case CODE_ARCHIVE -> "source-archive";
            case GIT_REPO, REQUIREMENT_DOC, MANUAL_SELECTION -> null;
        };
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

    private Map<String, Object> springParameters(MethodDeclaration method) {
        var parameters = new LinkedHashMap<String, Object>();
        for (var parameter : method.getParameters()) {
            for (var annotation : parameter.getAnnotations()) {
                switch (annotationName(annotation)) {
                    case "PathVariable" -> parameterBucket(parameters, "path").add(parameterShape(annotation, parameter, true));
                    case "RequestParam" -> parameterBucket(parameters, "query").add(parameterShape(annotation, parameter, required(annotation, true)));
                    case "RequestHeader" -> parameterBucket(parameters, "header").add(parameterShape(annotation, parameter, required(annotation, true)));
                    case "RequestBody" -> parameters.put("requestBody", requestBodyShape(annotation, parameter));
                    default -> {
                    }
                }
            }
        }
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

    private Map<String, Object> requestBodyShape(AnnotationExpr annotation, com.github.javaparser.ast.body.Parameter parameter) {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", parameter.getNameAsString());
        body.put("type", parameter.getTypeAsString());
        body.put("required", required(annotation, true));
        return body;
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

    private String jsonPointerPath(String path) {
        return path.replace("~", "~0").replace("/", "~1");
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

    private void succeedStep(PlanStep step, String detail) {
        step.setStepStatus(PlanStepStatus.SUCCESS);
        step.setInputRef(detail);
        step.setFinishedAt(Instant.now());
        planSteps.save(step);
    }

    private void failStep(PlanStep step, String errorCode, String errorMessage) {
        step.setStepStatus(PlanStepStatus.FAILED);
        step.setInputRef(errorCode + ": " + errorMessage);
        step.setFinishedAt(Instant.now());
        planSteps.save(step);
    }

    private void skipStep(PlanStep step, String reason) {
        step.setStepStatus(PlanStepStatus.SKIPPED);
        step.setInputRef(reason);
        step.setFinishedAt(Instant.now());
        planSteps.save(step);
    }

    private ApiAnalysisResult failAnalysis(
        SourceMaterial material,
        Task task,
        String parserRoute,
        String errorCode,
        String errorMessage
    ) {
        task.setStatus(TaskStatus.FAILED);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("errorCode", errorCode);
        metadata.put("errorMessage", errorMessage);
        if (parserRoute != null) {
            metadata.put("parserRoute", parserRoute);
        }
        task.setMetadata(metadata);
        tasks.save(task);

        material.setIngestStatus(IngestStatus.FAILED);
        sourceMaterials.save(material);

        return ApiAnalysisResult.failure(material.getMaterialId(), task.getTaskId(), parserRoute, errorCode, errorMessage);
    }

    private record MaterialValidation(boolean valid, String errorCode, String errorMessage) {

        static MaterialValidation ok() {
            return new MaterialValidation(true, null, null);
        }

        static MaterialValidation invalid(String errorCode, String errorMessage) {
            return new MaterialValidation(false, errorCode, errorMessage);
        }
    }

    private record OpenApiParseOutcome(
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

    private record SpringSourceParseOutcome(
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

    private record ParsedOpenApiOperation(
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

    private record ParsedSpringOperation(
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

    private record SpringMapping(List<String> paths, List<HttpMethod> httpMethods) {
    }
}
