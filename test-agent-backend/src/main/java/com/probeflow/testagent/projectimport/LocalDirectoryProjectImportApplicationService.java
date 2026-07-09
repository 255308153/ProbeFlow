package com.probeflow.testagent.projectimport;

import com.probeflow.testagent.analysis.ApiAnalysisApplicationService;
import com.probeflow.testagent.analysis.ApiAnalysisRequest;
import com.probeflow.testagent.analysis.ApiAnalysisResult;
import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.sourcematerial.IngestStatus;
import com.probeflow.testagent.sourcematerial.MaterialType;
import com.probeflow.testagent.sourcematerial.SourceMaterial;
import com.probeflow.testagent.sourcematerial.SourceMaterialRepository;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalDirectoryProjectImportApplicationService {

    private static final String ALLOWED_ROOTS_PROPERTY =
        "probeflow.project-import.local-directory.allowed-roots";
    private static final Set<String> SOURCE_LOCATION_SOURCE_BODY_KEYS = Set.of(
        "source",
        "sourcecode",
        "source_code",
        "sourcesnippet",
        "source_snippet",
        "sourcetext",
        "source_text",
        "rawsource",
        "raw_source",
        "code",
        "content",
        "body",
        "text",
        "snippet"
    );

    private final LocalDirectoryPathValidator pathValidator;
    private final ApiAnalysisApplicationService apiAnalysis;
    private final SourceMaterialRepository sourceMaterials;
    private final TaskRepository tasks;
    private final ApiSpecRepository apiSpecs;

    @Autowired
    public LocalDirectoryProjectImportApplicationService(
        Environment environment,
        ApiAnalysisApplicationService apiAnalysis,
        SourceMaterialRepository sourceMaterials,
        TaskRepository tasks,
        ApiSpecRepository apiSpecs
    ) {
        this(new LocalDirectoryPathValidator(LocalDirectoryPathValidator.splitAllowedRoots(
            environment.getProperty(ALLOWED_ROOTS_PROPERTY, "")
        )), apiAnalysis, sourceMaterials, tasks, apiSpecs);
    }

    LocalDirectoryProjectImportApplicationService(LocalDirectoryPathValidator pathValidator) {
        this(pathValidator, null, null, null, null);
    }

    LocalDirectoryProjectImportApplicationService(
        LocalDirectoryPathValidator pathValidator,
        ApiAnalysisApplicationService apiAnalysis
    ) {
        this(pathValidator, apiAnalysis, null, null, null);
    }

    LocalDirectoryProjectImportApplicationService(
        LocalDirectoryPathValidator pathValidator,
        ApiAnalysisApplicationService apiAnalysis,
        SourceMaterialRepository sourceMaterials,
        TaskRepository tasks,
        ApiSpecRepository apiSpecs
    ) {
        this.pathValidator = pathValidator;
        this.apiAnalysis = apiAnalysis;
        this.sourceMaterials = sourceMaterials;
        this.tasks = tasks;
        this.apiSpecs = apiSpecs;
    }

    public ProjectImportResponse importLocalDirectory(ProjectImportLocalDirectoryRequest request) {
        if (request == null) {
            throw new ProjectImportValidationException(ProjectImportErrorResponse.validation(
                "LOCAL_DIRECTORY_IMPORT_REQUEST_REQUIRED",
                "body",
                "Request body is required for local directory import."
            ));
        }

        var realPath = pathValidator.validate(request.storagePath());
        if (apiAnalysis == null) {
            return ProjectImportResponse.validatedLocalDirectory();
        }

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.SOURCE_DIRECTORY,
            request.projectName(),
            request.storagePath(),
            storagePath(realPath),
            request.requestedBy()
        ));
        return toProjectImportResponse(result);
    }

    public ProjectImportResponse reanalyzeImport(String materialId) {
        var material = requireSourceDirectoryMaterial(materialId);
        var result = apiAnalysis.analyze(ApiAnalysisRequest.existingMaterial(
            material.getMaterialId(),
            "project-import-reanalysis"
        ));
        return toProjectImportResponse(result);
    }

    private ProjectImportResponse toProjectImportResponse(ApiAnalysisResult result) {
        if (result.succeeded()) {
            return ProjectImportResponse.readySourceDirectory(
                result.materialId(),
                result.taskId(),
                result.apiSpecIds().size()
            );
        }

        return ProjectImportResponse.failedSourceDirectory(
            result.materialId(),
            result.taskId(),
            ProjectImportDiagnostic.blocker(
                result.errorCode(),
                "Local directory analysis failed",
                result.errorMessage(),
                "Check that the directory contains analyzable Spring @RestController source code."
            )
        );
    }

    @Transactional(readOnly = true)
    public ProjectImportDetailResponse getImportDetail(String materialId) {
        var material = requireSourceDirectoryMaterial(materialId);
        var task = findTask(material);
        var specs = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(material.getMaterialId());

        return new ProjectImportDetailResponse(
            material.getMaterialId(),
            material.getMaterialType().name(),
            material.getOriginalName(),
            material.getOriginalRef(),
            material.getStoragePath(),
            material.getIngestStatus().name(),
            material.getTaskId(),
            specs.size(),
            lastAnalyzedAt(material, task),
            warnings(task),
            blockers(material, task)
        );
    }

    @Transactional(readOnly = true)
    public List<ProjectImportApiSpecResponse> listApiSpecs(String materialId) {
        var material = requireSourceDirectoryMaterial(materialId);
        return apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(material.getMaterialId()).stream()
            .sorted(Comparator
                .comparing(ApiSpec::getPath)
                .thenComparing(apiSpec -> apiSpec.getHttpMethod().name()))
            .map(this::toApiSpecResponse)
            .toList();
    }

    private SourceMaterial requireSourceDirectoryMaterial(String materialId) {
        if (materialId == null || materialId.isBlank()) {
            throw new ProjectImportValidationException(ProjectImportErrorResponse.validation(
                "PROJECT_IMPORT_MATERIAL_ID_REQUIRED",
                "materialId",
                "Input material id is required."
            ));
        }

        var material = sourceMaterials.findById(materialId)
            .orElseThrow(() -> new ProjectImportNotFoundException(ProjectImportErrorResponse.notFound(
                "PROJECT_IMPORT_NOT_FOUND",
                "materialId",
                "Project import was not found."
            )));
        if (material.getMaterialType() != MaterialType.SOURCE_DIRECTORY) {
            throw new ProjectImportValidationException(ProjectImportErrorResponse.boundary(
                "PROJECT_IMPORT_NOT_SOURCE_DIRECTORY",
                "materialId",
                "Input material is not a local source directory import."
            ));
        }
        return material;
    }

    private Task findTask(SourceMaterial material) {
        if (material.getTaskId() == null || material.getTaskId().isBlank()) {
            return null;
        }
        return tasks.findById(material.getTaskId()).orElse(null);
    }

    private Instant lastAnalyzedAt(SourceMaterial material, Task task) {
        if (task != null && task.getUpdatedAt() != null) {
            return task.getUpdatedAt();
        }
        return material.getUpdatedAt();
    }

    private List<ProjectImportDiagnostic> warnings(Task task) {
        if (task == null || task.getMetadata() == null) {
            return List.of();
        }
        var warnings = task.getMetadata().get("warnings");
        if (!(warnings instanceof List<?> warningList)) {
            return List.of();
        }
        return warningList.stream()
            .map(String::valueOf)
            .filter(message -> !message.isBlank())
            .map(message -> ProjectImportDiagnostic.warning(
                "ANALYSIS_WARNING",
                "Local directory analysis warning",
                message,
                "Review the source project and rerun analysis if needed."
            ))
            .toList();
    }

    private List<ProjectImportDiagnostic> blockers(SourceMaterial material, Task task) {
        if (material.getIngestStatus() != IngestStatus.FAILED) {
            return List.of();
        }
        var metadata = task == null || task.getMetadata() == null ? Map.<String, Object>of() : task.getMetadata();
        var errorCode = text(metadata.get("errorCode"), "LOCAL_DIRECTORY_IMPORT_FAILED");
        var errorMessage = text(metadata.get("errorMessage"), "Local directory import failed.");
        return List.of(ProjectImportDiagnostic.blocker(
            errorCode,
            "Local directory analysis failed",
            errorMessage,
            "Check that the directory contains analyzable Spring @RestController source code."
        ));
    }

    private ProjectImportApiSpecResponse toApiSpecResponse(ApiSpec apiSpec) {
        return new ProjectImportApiSpecResponse(
            apiSpec.getApiSpecId(),
            apiSpec.getHttpMethod().name(),
            apiSpec.getPath(),
            apiSpec.getModuleName(),
            apiSpec.getSummary(),
            sourceLocationWithoutSourceBody(apiSpec.getSourceLocation()),
            apiSpec.getSourceMaterialId(),
            apiSpec.isRouteReady(),
            apiSpec.isBasicParamReady(),
            apiSpec.isDtoExpanded(),
            apiSpec.isValidationReady(),
            apiSpec.isAuthReady(),
            apiSpec.isKnowledgeContextReady(),
            apiSpec.isPresentInLatestAnalysis()
        );
    }

    private Map<String, Object> sourceLocationWithoutSourceBody(Map<String, Object> sourceLocation) {
        if (sourceLocation == null || sourceLocation.isEmpty()) {
            return Map.of();
        }
        return sourceLocationMapWithoutSourceBody(sourceLocation);
    }

    private Map<String, Object> sourceLocationMapWithoutSourceBody(Map<?, ?> sourceLocation) {
        var sanitized = new LinkedHashMap<String, Object>();
        sourceLocation.forEach((key, value) -> {
            if (key != null && !SOURCE_LOCATION_SOURCE_BODY_KEYS.contains(String.valueOf(key).toLowerCase(Locale.ROOT))) {
                sanitized.put(String.valueOf(key), sourceLocationValueWithoutSourceBody(value));
            }
        });
        return sanitized;
    }

    private Object sourceLocationValueWithoutSourceBody(Object value) {
        if (value instanceof Map<?, ?> nestedMap) {
            return sourceLocationMapWithoutSourceBody(nestedMap);
        }
        if (value instanceof List<?> nestedList) {
            return nestedList.stream()
                .map(this::sourceLocationValueWithoutSourceBody)
                .toList();
        }
        return value;
    }

    private String text(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private String storagePath(Path realPath) {
        return realPath.toString();
    }
}
