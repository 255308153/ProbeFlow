package com.probeflow.testagent.analysis;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiAnalysisApplicationService {

    private final SourceMaterialRepository sourceMaterials;
    private final ApiSpecRepository apiSpecs;
    private final TaskRepository tasks;
    private final PlanStepRepository planSteps;
    private final SpringSourceAnalyzer springSourceAnalyzer;
    private final OpenApiSourceAnalyzer openApiSourceAnalyzer;

    public ApiAnalysisApplicationService(
        SourceMaterialRepository sourceMaterials,
        ApiSpecRepository apiSpecs,
        TaskRepository tasks,
        PlanStepRepository planSteps,
        SpringSourceAnalyzer springSourceAnalyzer,
        OpenApiSourceAnalyzer openApiSourceAnalyzer
    ) {
        this.sourceMaterials = sourceMaterials;
        this.apiSpecs = apiSpecs;
        this.tasks = tasks;
        this.planSteps = planSteps;
        this.springSourceAnalyzer = springSourceAnalyzer;
        this.openApiSourceAnalyzer = openApiSourceAnalyzer;
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
        if (material.getMaterialType() == MaterialType.CODE_ARCHIVE) {
            return analyzeSourceArchive(material, task, route);
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
        return analyzeSpringSourcePath(material, task, route, Path.of(material.getStoragePath()), 3);
    }

    private ApiAnalysisResult analyzeSourceArchive(SourceMaterial material, Task task, String route) {
        var unpackStep = createStep(task.getTaskId(), 3, "Unpack source archive", material.getStoragePath());
        var unpackResult = unpackSourceArchive(material);
        if (!unpackResult.succeeded()) {
            failStep(unpackStep, unpackResult.errorCode(), unpackResult.errorMessage());
            return failAnalysis(material, task, route, unpackResult.errorCode(), unpackResult.errorMessage());
        }

        succeedStep(unpackStep, "Archive unpacked for source analysis");
        try {
            return analyzeSpringSourcePath(material, task, route, unpackResult.sourceRoot(), 4);
        } finally {
            deleteRecursively(unpackResult.sourceRoot());
        }
    }

    private ApiAnalysisResult analyzeSpringSourcePath(
        SourceMaterial material,
        Task task,
        String route,
        Path sourceRoot,
        int parseStepOrder
    ) {
        var parseStep = createStep(task.getTaskId(), parseStepOrder, "Parse Spring controller source", sourceRoot.toString());
        var mergeStep = createStep(task.getTaskId(), parseStepOrder + 1, "Persist ApiSpec operations", material.getMaterialId());

        var parseResult = springSourceAnalyzer.analyze(material, sourceRoot);
        if (!parseResult.succeeded()) {
            failStep(parseStep, parseResult.errorCode(), parseResult.errorMessage());
            skipStep(mergeStep, "Spring source parse failed");
            return failAnalysis(material, task, route, parseResult.errorCode(), parseResult.errorMessage());
        }
        var parseMessage = "Parsed Spring routes: " + parseResult.operations().size();
        if (!parseResult.warnings().isEmpty()) {
            parseMessage += "; warnings: " + parseResult.warnings().size();
        }
        succeedStep(parseStep, parseMessage);

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
        task.setMetadata(successMetadata(route, apiSpecIds.size(), parseResult.warnings()));
        tasks.save(task);

        material.setIngestStatus(IngestStatus.READY);
        sourceMaterials.save(material);

        return ApiAnalysisResult.success(material.getMaterialId(), task.getTaskId(), route, apiSpecIds);
    }

    private ApiAnalysisResult analyzeOpenApi(SourceMaterial material, Task task, String route) {
        var parseStep = createStep(task.getTaskId(), 3, "Parse OpenAPI document", material.getStoragePath());
        var mergeStep = createStep(task.getTaskId(), 4, "Persist ApiSpec operations", material.getMaterialId());

        var parseResult = openApiSourceAnalyzer.analyze(material.getStoragePath());
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
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
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

    private ArchiveUnpackOutcome unpackSourceArchive(SourceMaterial material) {
        var archivePath = Path.of(material.getStoragePath());
        if (!archivePath.getFileName().toString().toLowerCase().endsWith(".zip")) {
            return ArchiveUnpackOutcome.failure("ARCHIVE_UNSUPPORTED_FORMAT", "Only .zip source archives are supported");
        }

        Path sourceRoot = null;
        try {
            sourceRoot = Files.createTempDirectory("probeflow-source-archive-").toAbsolutePath().normalize();
            try (var zip = new ZipInputStream(Files.newInputStream(archivePath))) {
                var entry = zip.getNextEntry();
                while (entry != null) {
                    var destination = sourceRoot.resolve(entry.getName()).normalize();
                    if (!destination.startsWith(sourceRoot)) {
                        deleteRecursively(sourceRoot);
                        return ArchiveUnpackOutcome.failure("ARCHIVE_UNSAFE_ENTRY", "Archive entry escapes extraction root: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(destination);
                    } else {
                        var parent = destination.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.copy(zip, destination);
                    }
                    zip.closeEntry();
                    entry = zip.getNextEntry();
                }
            }
            return ArchiveUnpackOutcome.success(sourceRoot);
        } catch (IOException exception) {
            if (sourceRoot != null) {
                deleteRecursively(sourceRoot);
            }
            return ArchiveUnpackOutcome.failure("ARCHIVE_UNPACK_FAILED", exception.getMessage());
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
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
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
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

    private Map<String, Object> successMetadata(String parserRoute, int apiSpecCount, List<String> warnings) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("parserRoute", parserRoute);
        metadata.put("apiSpecCount", apiSpecCount);
        if (!warnings.isEmpty()) {
            metadata.put("warningCount", warnings.size());
            metadata.put("warnings", warnings);
        }
        return metadata;
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

    private record ArchiveUnpackOutcome(
        boolean succeeded,
        Path sourceRoot,
        String errorCode,
        String errorMessage
    ) {

        static ArchiveUnpackOutcome success(Path sourceRoot) {
            return new ArchiveUnpackOutcome(true, sourceRoot, null, null);
        }

        static ArchiveUnpackOutcome failure(String errorCode, String errorMessage) {
            return new ArchiveUnpackOutcome(false, null, errorCode, errorMessage);
        }
    }
}
