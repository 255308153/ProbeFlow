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
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecution;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionRepository;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionStatus;
import com.probeflow.testagent.testcase.StaleStatus;
import com.probeflow.testagent.testcase.TestCase;
import com.probeflow.testagent.testcase.TestCaseRepository;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskInitializationService {

    private final TaskRepository tasks;
    private final PlanStepRepository planSteps;
    private final TaskTemplateRegistry taskTemplateRegistry;
    private final TestCaseRepository testCases;
    private final TaskCaseExecutionRepository taskCaseExecutions;

    public TaskInitializationService(
        TaskRepository tasks,
        PlanStepRepository planSteps,
        TaskTemplateRegistry taskTemplateRegistry,
        TestCaseRepository testCases,
        TaskCaseExecutionRepository taskCaseExecutions
    ) {
        this.tasks = tasks;
        this.planSteps = planSteps;
        this.taskTemplateRegistry = taskTemplateRegistry;
        this.testCases = testCases;
        this.taskCaseExecutions = taskCaseExecutions;
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
        var regressionCaseSelection = regressionCaseSelection(request);
        var metadata = initialMetadata(request, template);
        enrichRegressionMetadata(metadata, regressionCaseSelection);
        task.setMetadata(metadata);

        var savedTask = tasks.save(task);
        prepareRegressionCaseExecutions(savedTask.getTaskId(), regressionCaseSelection);
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

    private RegressionCaseSelection regressionCaseSelection(TaskInitializationRequest request) {
        var selectedCaseIds = normalizeList(request.selectedCaseIds());
        if (request.taskType() != TaskType.REGRESSION || selectedCaseIds.isEmpty()) {
            return RegressionCaseSelection.empty();
        }
        var casesById = new LinkedHashMap<String, TestCase>();
        testCases.findAllById(selectedCaseIds)
            .forEach(testCase -> casesById.put(testCase.getCaseId(), testCase));
        var selectedCases = selectedCaseIds.stream()
            .map(casesById::get)
            .filter(Objects::nonNull)
            .toList();
        var missingCaseIds = selectedCaseIds.stream()
            .filter(caseId -> !casesById.containsKey(caseId))
            .toList();
        var staleCases = selectedCases.stream()
            .filter(testCase -> testCase.getStaleStatus() == StaleStatus.STALE)
            .toList();
        return new RegressionCaseSelection(selectedCases, missingCaseIds, staleCases);
    }

    private void enrichRegressionMetadata(Map<String, Object> metadata, RegressionCaseSelection selection) {
        if (selection.isEmpty()) {
            return;
        }
        metadata.put("regressionCaseCount", selection.selectedCases().size());
        if (!selection.missingCaseIds().isEmpty()) {
            metadata.put("missingCaseIds", selection.missingCaseIds());
            metadata.put("missingCaseCount", selection.missingCaseIds().size());
        }
        if (!selection.staleCases().isEmpty()) {
            metadata.put("staleCaseIds", selection.staleCases().stream()
                .map(TestCase::getCaseId)
                .toList());
            metadata.put("staleCaseCount", selection.staleCases().size());
            metadata.put("staleCaseDetails", selection.staleCases().stream()
                .map(this::staleCaseDetail)
                .toList());
        }
    }

    private Map<String, Object> staleCaseDetail(TestCase testCase) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("caseId", testCase.getCaseId());
        detail.put("title", testCase.getTitle());
        detail.put("primaryApiSpecId", testCase.getPrimaryApiSpecId());
        detail.put("staleStatus", enumName(testCase.getStaleStatus()));
        return detail;
    }

    private void prepareRegressionCaseExecutions(String taskId, RegressionCaseSelection selection) {
        for (var testCase : selection.selectedCases()) {
            if (taskCaseExecutions.findFirstByTaskIdAndCaseId(taskId, testCase.getCaseId()).isPresent()) {
                continue;
            }
            var execution = new TaskCaseExecution();
            execution.setTaskId(taskId);
            execution.setCaseId(testCase.getCaseId());
            execution.setExecutionMode(ExecutionMode.BATCH);
            execution.setExecutionStatus(TaskCaseExecutionStatus.PENDING);
            execution.setSnapshotJson(caseSnapshot(testCase));
            taskCaseExecutions.save(execution);
        }
    }

    private Map<String, Object> caseSnapshot(TestCase testCase) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("caseId", testCase.getCaseId());
        snapshot.put("title", testCase.getTitle());
        snapshot.put("primaryApiSpecId", testCase.getPrimaryApiSpecId());
        snapshot.put("caseCategory", enumName(testCase.getCaseCategory()));
        snapshot.put("mode", enumName(testCase.getMode()));
        snapshot.put("priority", enumName(testCase.getPriority()));
        snapshot.put("riskLevel", enumName(testCase.getRiskLevel()));
        snapshot.put("status", enumName(testCase.getStatus()));
        snapshot.put("source", enumName(testCase.getSource()));
        snapshot.put("detailType", enumName(testCase.getDetailType()));
        snapshot.put("detail", copyMap(testCase.getDetail()));
        snapshot.put("steps", copyList(testCase.getSteps()));
        snapshot.put("tags", copyList(testCase.getTags()));
        snapshot.put("staleStatus", enumName(testCase.getStaleStatus()));
        snapshot.put("basedOnApiSpecVersions", copyMap(testCase.getBasedOnApiSpecVersions()));
        return snapshot;
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private Map<String, Object> copyMap(Map<String, Object> values) {
        return values == null ? Map.of() : new LinkedHashMap<>(values);
    }

    private List<?> copyList(List<?> values) {
        return values == null ? List.of() : List.copyOf(values);
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

    private record RegressionCaseSelection(
        List<TestCase> selectedCases,
        List<String> missingCaseIds,
        List<TestCase> staleCases
    ) {
        private static RegressionCaseSelection empty() {
            return new RegressionCaseSelection(List.of(), List.of(), List.of());
        }

        private boolean isEmpty() {
            return selectedCases.isEmpty() && missingCaseIds.isEmpty() && staleCases.isEmpty();
        }
    }
}
