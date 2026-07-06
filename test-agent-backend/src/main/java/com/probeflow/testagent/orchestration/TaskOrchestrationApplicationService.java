package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.controlledplanner.PlannerConstraint;
import com.probeflow.testagent.replanning.ReplanningApplicationService;
import com.probeflow.testagent.replanning.ReplanningRequest;
import com.probeflow.testagent.replanning.ReplanningResult;
import com.probeflow.testagent.replanning.ReplanningStatus;
import com.probeflow.testagent.replanning.ReplanningTrigger;
import com.probeflow.testagent.task.PlanStep;
import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.PlanStepType;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskOrchestrationApplicationService {

    private static final String REPLANNING_ENABLED_KEY = "replanningEnabled";
    private static final String REPLANNING_CONSTRAINTS_KEY = "replanningConstraints";
    private static final String LAST_ORCHESTRATION_REPLANNING_KEY = "lastOrchestrationReplanning";
    private static final String REPLANNING_ATTEMPT_RECORDS_KEY = "replanningAttemptRecords";

    private final TaskRepository tasks;
    private final PlanStepRepository planSteps;
    private final PlanStepRunner planStepRunner;
    private final ManualReviewGate manualReviewGate;
    private final ReplanningApplicationService replanning;

    public TaskOrchestrationApplicationService(
        TaskRepository tasks,
        PlanStepRepository planSteps,
        PlanStepRunner planStepRunner,
        ManualReviewGate manualReviewGate,
        ReplanningApplicationService replanning
    ) {
        this.tasks = tasks;
        this.planSteps = planSteps;
        this.planStepRunner = planStepRunner;
        this.manualReviewGate = manualReviewGate;
        this.replanning = replanning;
    }

    @Transactional
    public TaskOrchestrationResult runInitializedTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalArgumentException("Task id is required");
        }
        var task = tasks.findById(taskId.trim())
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        var blockers = new ArrayList<String>();
        var orderedSteps = planSteps.findByTaskIdOrderByStepOrderAsc(task.getTaskId());
        String reportId = metadataString(task, "reportId");

        if (task.getStatus() == TaskStatus.CANCELLED) {
            blockers.add("Task is cancelled");
            return result(task, orderedSteps, reportId, blockers);
        }
        if (orderedSteps.isEmpty()) {
            blockers.add("Task has no PlanSteps");
            task.setStatus(TaskStatus.FAILED);
            tasks.save(task);
            return result(task, orderedSteps, reportId, blockers);
        }
        if (task.getStatus() == TaskStatus.WAITING_FOR_REVIEW) {
            var reviewGate = manualReviewGate.evaluate(task);
            if (!reviewGate.ready()) {
                blockers.addAll(reviewGate.blockerDetails());
                return result(task, orderedSteps, reportId, blockers);
            }
            applyReviewGate(task, reviewGate);
            task.setStatus(TaskStatus.CASE_GENERATED);
            tasks.save(task);
        }

        for (int i = 0; i < orderedSteps.size(); i++) {
            task = tasks.findById(task.getTaskId()).orElseThrow();
            if (task.getStatus() == TaskStatus.CANCELLED) {
                blockers.add("Task is cancelled");
                return result(task, orderedSteps, reportId, blockers);
            }

            var step = orderedSteps.get(i);
            if (step.getStepStatus() == PlanStepStatus.SUCCESS || step.getStepStatus() == PlanStepStatus.SKIPPED) {
                continue;
            }
            if (step.getStepStatus() == PlanStepStatus.FAILED) {
                if (hasAppliedRecoveryForFailedStep(task, step)) {
                    continue;
                }
                blockers.add("PlanStep failed: " + step.getStepType());
                var replanningResult = maybeReplan(
                    task,
                    step,
                    StepOutcome.failed("PlanStep failed: " + step.getStepType()),
                    ReplanningTrigger.PLAN_STEP_FAILED
                );
                if (replanningHandled(replanningResult)) {
                    blockers.addAll(replanningBlockers(replanningResult));
                    orderedSteps = planSteps.findByTaskIdOrderByStepOrderAsc(task.getTaskId());
                    task = tasks.findById(task.getTaskId()).orElseThrow();
                    return result(task, orderedSteps, reportId, blockers);
                }
                skipDownstream(orderedSteps, i + 1);
                task.setStatus(TaskStatus.FAILED);
                tasks.save(task);
                return result(task, orderedSteps, reportId, blockers);
            }

            var outcome = runStep(task, step);
            if (step.getStepType() == PlanStepType.GENERATE_REPORT && !outcome.resultRefs().isEmpty()) {
                reportId = outcome.resultRefs().getFirst();
                task = tasks.findById(task.getTaskId()).orElseThrow();
                putMetadata(task, "reportId", reportId);
                tasks.save(task);
            }
            blockers.addAll(outcome.blockerDetails());
            if (outcome.stepStatus() == PlanStepStatus.FAILED) {
                var replanningResult = maybeReplan(task, step, outcome, ReplanningTrigger.PLAN_STEP_FAILED);
                if (replanningHandled(replanningResult)) {
                    blockers.addAll(replanningBlockers(replanningResult));
                    orderedSteps = planSteps.findByTaskIdOrderByStepOrderAsc(task.getTaskId());
                    task = tasks.findById(task.getTaskId()).orElseThrow();
                    return result(task, orderedSteps, reportId, blockers);
                }
                skipDownstream(orderedSteps, i + 1);
                task = tasks.findById(task.getTaskId()).orElseThrow();
                return result(task, orderedSteps, reportId, blockers);
            }
            if (outcome.stepStatus() == PlanStepStatus.SKIPPED && !outcome.blockerDetails().isEmpty()) {
                var replanningResult = maybeReplan(task, step, outcome, ReplanningTrigger.EXECUTION_READINESS_MISSING);
                if (replanningHandled(replanningResult)) {
                    blockers.addAll(replanningBlockers(replanningResult));
                    orderedSteps = planSteps.findByTaskIdOrderByStepOrderAsc(task.getTaskId());
                    task = tasks.findById(task.getTaskId()).orElseThrow();
                    return result(task, orderedSteps, reportId, blockers);
                }
            }
            if (outcome.stopOrchestration()) {
                task = tasks.findById(task.getTaskId()).orElseThrow();
                return result(task, orderedSteps, reportId, blockers);
            }
        }

        task = tasks.findById(task.getTaskId()).orElseThrow();
        if (task.getStatus() != TaskStatus.CANCELLED && task.getStatus() != TaskStatus.FAILED) {
            task.setStatus(TaskStatus.COMPLETED);
            tasks.save(task);
        }
        return result(task, orderedSteps, reportId, blockers);
    }

    private StepOutcome runStep(Task task, PlanStep step) {
        task.setStatus(statusFor(step.getStepType()));
        tasks.save(task);

        step.setStepStatus(PlanStepStatus.RUNNING);
        if (step.getStartedAt() == null) {
            step.setStartedAt(Instant.now());
        }
        planSteps.save(step);

        var outcome = planStepRunner.run(task, step);
        step.setStepStatus(outcome.stepStatus());
        step.setFinishedAt(Instant.now());
        planSteps.save(step);

        if (outcome.taskStatus() != null) {
            task.setStatus(outcome.taskStatus());
        } else if (outcome.stepStatus() == PlanStepStatus.FAILED) {
            task.setStatus(TaskStatus.FAILED);
        } else if (outcome.stepStatus() == PlanStepStatus.SUCCESS && step.getStepType() == PlanStepType.GENERATE_CASES) {
            task.setStatus(TaskStatus.CASE_GENERATED);
        }
        tasks.save(task);
        return outcome;
    }

    private TaskStatus statusFor(PlanStepType stepType) {
        return switch (stepType) {
            case ANALYZE_CODE_API, RETRIEVE_KNOWLEDGE, GENERATE_CASES -> TaskStatus.ANALYZING;
            case EXECUTE_SINGLE, EXECUTE_BATCH, EXECUTE_SUITE -> TaskStatus.EXECUTING;
            case ANALYZE_FAILURE, GENERATE_REPORT, REFINE_MEMORY -> TaskStatus.ANALYZING_RESULTS;
        };
    }

    private void skipDownstream(List<PlanStep> orderedSteps, int startIndex) {
        for (int i = startIndex; i < orderedSteps.size(); i++) {
            var step = orderedSteps.get(i);
            if (step.getStepStatus() == PlanStepStatus.PENDING || step.getStepStatus() == PlanStepStatus.RUNNING) {
                step.setStepStatus(PlanStepStatus.SKIPPED);
                step.setFinishedAt(Instant.now());
                planSteps.save(step);
            }
        }
    }

    private ReplanningResult maybeReplan(
        Task task,
        PlanStep sourceStep,
        StepOutcome outcome,
        ReplanningTrigger trigger
    ) {
        if (!replanningEnabled(task)) {
            return null;
        }
        var result = replanning.replan(new ReplanningRequest(
            task.getTaskId(),
            trigger,
            sourceStep.getStepId(),
            outcome,
            Map.of(),
            false,
            null,
            null,
            replanningConstraints(task)
        ));
        recordOrchestrationReplanning(task.getTaskId(), trigger, sourceStep, result);
        return result;
    }

    private boolean replanningHandled(ReplanningResult result) {
        return result != null
            && (result.status() == ReplanningStatus.APPLIED || result.status() == ReplanningStatus.WAITING_FOR_HUMAN);
    }

    private List<String> replanningBlockers(ReplanningResult result) {
        if (result == null) {
            return List.of();
        }
        var blockers = new ArrayList<String>();
        blockers.add("Replanning " + result.status().name() + " for " + result.trigger().name());
        blockers.addAll(result.blockers());
        return blockers;
    }

    private void recordOrchestrationReplanning(
        String taskId,
        ReplanningTrigger trigger,
        PlanStep sourceStep,
        ReplanningResult result
    ) {
        var task = tasks.findById(taskId).orElseThrow();
        var metadata = task.getMetadata() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<>(task.getMetadata());
        metadata.put(LAST_ORCHESTRATION_REPLANNING_KEY, Map.of(
            "source", "TaskOrchestrationApplicationService",
            "limitedIntegration", true,
            "trigger", trigger.name(),
            "sourceStepId", sourceStep.getStepId(),
            "status", result.status().name(),
            "insertedStepIds", result.insertedStepIds(),
            "skippedStepIds", result.skippedStepIds(),
            "blockers", result.blockers()
        ));
        task.setMetadata(metadata);
        if (result.status() == ReplanningStatus.APPLIED && task.getStatus() == TaskStatus.FAILED) {
            task.setStatus(TaskStatus.ANALYZING_RESULTS);
        }
        tasks.save(task);
    }

    private boolean hasAppliedRecoveryForFailedStep(Task task, PlanStep step) {
        var records = metadataMap(task, REPLANNING_ATTEMPT_RECORDS_KEY);
        var triggerAttemptKey = ReplanningTrigger.PLAN_STEP_FAILED.name() + "|" + step.getStepId();
        return records.values().stream()
            .filter(value -> value instanceof Map<?, ?>)
            .map(value -> (Map<?, ?>) value)
            .anyMatch(record -> triggerAttemptKey.equals(stringValue(record.get("triggerAttemptKey")))
                && ReplanningStatus.APPLIED.name().equals(stringValue(record.get("status"))));
    }

    private boolean replanningEnabled(Task task) {
        var value = task.getMetadata() == null ? null : task.getMetadata().get(REPLANNING_ENABLED_KEY);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private List<PlannerConstraint> replanningConstraints(Task task) {
        var value = task.getMetadata() == null ? null : task.getMetadata().get(REPLANNING_CONSTRAINTS_KEY);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        var constraints = new ArrayList<PlannerConstraint>();
        for (var item : values) {
            if (item instanceof Map<?, ?> map) {
                var code = stringValue(map.get("code"));
                var description = stringValue(map.get("description"));
                if (StringUtils.hasText(code) && StringUtils.hasText(description)) {
                    constraints.add(PlannerConstraint.of(code, description));
                }
            }
        }
        return List.copyOf(constraints);
    }

    private void applyReviewGate(Task task, ManualReviewGateResult reviewGate) {
        var metadata = task.getMetadata() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<>(task.getMetadata());
        metadata.put("selectedCaseIds", reviewGate.promotedCaseIds());
        metadata.put("promotedCaseIds", reviewGate.promotedCaseIds());
        metadata.put("discardedDraftIds", reviewGate.discardedDraftIds());
        task.setMetadata(metadata);
    }

    private Map<String, Object> metadataMap(Task task, String key) {
        var value = task.getMetadata() == null ? null : task.getMetadata().get(key);
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, Object>();
        map.forEach((entryKey, entryValue) -> {
            if (entryKey != null && StringUtils.hasText(entryKey.toString())) {
                result.put(entryKey.toString(), entryValue);
            }
        });
        return Map.copyOf(result);
    }

    private String metadataString(Task task, String key) {
        var value = task.getMetadata() == null ? null : task.getMetadata().get(key);
        return value == null || !StringUtils.hasText(value.toString()) ? null : value.toString();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private void putMetadata(Task task, String key, Object value) {
        var metadata = task.getMetadata() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<>(task.getMetadata());
        metadata.put(key, value);
        task.setMetadata(metadata);
    }

    private TaskOrchestrationResult result(
        Task task,
        List<PlanStep> orderedSteps,
        String reportId,
        List<String> blockers
    ) {
        var completedStepCount = (int) orderedSteps.stream()
            .filter(step -> step.getStepStatus() == PlanStepStatus.SUCCESS)
            .count();
        return new TaskOrchestrationResult(task.getTaskId(), task.getStatus(), completedStepCount, reportId, blockers);
    }
}
