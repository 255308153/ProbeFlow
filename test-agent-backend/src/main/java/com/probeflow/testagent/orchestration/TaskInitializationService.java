package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.PlanStep;
import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskInitializationService {

    private final TaskRepository tasks;
    private final PlanStepRepository planSteps;
    private final TaskTemplateRegistry taskTemplateRegistry;

    public TaskInitializationService(
        TaskRepository tasks,
        PlanStepRepository planSteps,
        TaskTemplateRegistry taskTemplateRegistry
    ) {
        this.tasks = tasks;
        this.planSteps = planSteps;
        this.taskTemplateRegistry = taskTemplateRegistry;
    }

    @Transactional
    public TaskInitializationResult initialize(TaskInitializationRequest request) {
        validate(request);
        if (StringUtils.hasText(request.existingTaskId())) {
            return prepareExistingTask(request);
        }
        return createTask(request);
    }

    private TaskInitializationResult createTask(TaskInitializationRequest request) {
        var promotionMode = promotionMode(request);
        var template = taskTemplateRegistry.templateFor(request.taskType(), request.sourceType(), promotionMode);
        var task = new Task();
        task.setTaskType(request.taskType());
        task.setTaskName(request.taskName().trim());
        task.setStatus(TaskStatus.PENDING);
        task.setSourceType(sourceType(request));
        task.setSourceRef(trimToNull(request.sourceRef()));
        task.setTargetApiSpecIds(normalizeList(request.targetApiSpecIds()));
        task.setPromotionMode(promotionMode);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.NOT_REQUIRED);
        task.setPriority(priority(request));
        task.setCreator(trimToNull(request.creator()));
        task.setMetadata(initialMetadata(request, template));

        var savedTask = tasks.save(task);
        var savedSteps = createPlanSteps(savedTask.getTaskId(), template);
        return result(savedTask, template, savedSteps, true);
    }

    private TaskInitializationResult prepareExistingTask(TaskInitializationRequest request) {
        var task = tasks.findById(request.existingTaskId().trim())
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + request.existingTaskId()));
        var template = taskTemplateRegistry.templateFor(task.getTaskType(), task.getSourceType(), task.getPromotionMode());
        var existingSteps = planSteps.findByTaskIdOrderByStepOrderAsc(task.getTaskId());
        if (existingSteps.isEmpty()) {
            existingSteps = createPlanSteps(task.getTaskId(), template);
        }
        return result(task, template, existingSteps, false);
    }

    private List<PlanStep> createPlanSteps(String taskId, TaskTemplate template) {
        var saved = new ArrayList<PlanStep>();
        for (var stepTemplate : template.steps()) {
            var step = new PlanStep();
            step.setTaskId(taskId);
            step.setStepType(stepTemplate.stepType());
            step.setStepStatus(PlanStepStatus.PENDING);
            step.setStepOrder(stepTemplate.order());
            step.setGoal(stepTemplate.goal());
            step.setInputRef(taskId);
            step.setRetryCount(0);
            saved.add(planSteps.save(step));
        }
        return List.copyOf(saved);
    }

    private TaskInitializationResult result(
        Task task,
        TaskTemplate template,
        List<PlanStep> persistedSteps,
        boolean created
    ) {
        return new TaskInitializationResult(
            task.getTaskId(),
            task.getTaskType(),
            task.getStatus(),
            template.templateName(),
            persistedSteps.stream().map(PlanStep::getStepType).toList(),
            created
        );
    }

    private Map<String, Object> initialMetadata(TaskInitializationRequest request, TaskTemplate template) {
        var metadata = new LinkedHashMap<String, Object>();
        if (request.metadata() != null) {
            request.metadata().forEach((key, value) -> {
                if (StringUtils.hasText(key)) {
                    metadata.put(key.trim(), value);
                }
            });
        }
        metadata.put("phase", "9");
        metadata.put("templateName", template.templateName());
        metadata.put("initializedBy", "TaskInitializationService");
        metadata.put("targetApiSpecCount", normalizeList(request.targetApiSpecIds()).size());
        var selectedCaseIds = normalizeList(request.selectedCaseIds());
        if (!selectedCaseIds.isEmpty()) {
            metadata.put("selectedCaseIds", selectedCaseIds);
            metadata.put("selectedCaseCount", selectedCaseIds.size());
        }
        return metadata;
    }

    private void validate(TaskInitializationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Task initialization request is required");
        }
        if (StringUtils.hasText(request.existingTaskId())) {
            return;
        }
        if (request.taskType() == null) {
            throw new IllegalArgumentException("Task type is required");
        }
        if (!StringUtils.hasText(request.taskName())) {
            throw new IllegalArgumentException("Task name is required");
        }
    }

    private TaskSourceType sourceType(TaskInitializationRequest request) {
        return request.sourceType() == null ? TaskSourceType.MANUAL : request.sourceType();
    }

    private PromotionMode promotionMode(TaskInitializationRequest request) {
        return request.promotionMode() == null ? PromotionMode.AUTO : request.promotionMode();
    }

    private TaskPriority priority(TaskInitializationRequest request) {
        return request.priority() == null ? TaskPriority.MEDIUM : request.priority();
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
