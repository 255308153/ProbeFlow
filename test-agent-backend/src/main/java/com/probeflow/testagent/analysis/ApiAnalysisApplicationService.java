package com.probeflow.testagent.analysis;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiAnalysisApplicationService {

    private final SourceMaterialRepository sourceMaterials;
    private final TaskRepository tasks;
    private final PlanStepRepository planSteps;

    public ApiAnalysisApplicationService(
        SourceMaterialRepository sourceMaterials,
        TaskRepository tasks,
        PlanStepRepository planSteps
    ) {
        this.sourceMaterials = sourceMaterials;
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
        task.setStatus(TaskStatus.COMPLETED);
        task.setMetadata(Map.of("parserRoute", route, "apiSpecCount", 0));
        task.setTargetApiSpecIds(List.of());
        tasks.save(task);

        material.setIngestStatus(IngestStatus.READY);
        sourceMaterials.save(material);

        return ApiAnalysisResult.success(material.getMaterialId(), task.getTaskId(), route, List.of());
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
        task.setMetadata(Map.of(
            "errorCode", errorCode,
            "errorMessage", errorMessage
        ));
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
}
