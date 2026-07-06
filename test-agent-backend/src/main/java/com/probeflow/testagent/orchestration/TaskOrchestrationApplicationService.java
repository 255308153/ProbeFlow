package com.probeflow.testagent.orchestration;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskOrchestrationApplicationService {

    private final TaskRepository tasks;
    private final PlanStepRepository planSteps;
    private final PlanStepRunner planStepRunner;
    private final ManualReviewGate manualReviewGate;

    public TaskOrchestrationApplicationService(
        TaskRepository tasks,
        PlanStepRepository planSteps,
        PlanStepRunner planStepRunner,
        ManualReviewGate manualReviewGate
    ) {
        this.tasks = tasks;
        this.planSteps = planSteps;
        this.planStepRunner = planStepRunner;
        this.manualReviewGate = manualReviewGate;
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
        String reportId = null;

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
                blockers.add("PlanStep failed: " + step.getStepType());
                skipDownstream(orderedSteps, i + 1);
                task.setStatus(TaskStatus.FAILED);
                tasks.save(task);
                return result(task, orderedSteps, reportId, blockers);
            }

            var outcome = runStep(task, step);
            if (step.getStepType() == PlanStepType.GENERATE_REPORT && !outcome.resultRefs().isEmpty()) {
                reportId = outcome.resultRefs().getFirst();
            }
            blockers.addAll(outcome.blockerDetails());
            if (outcome.stepStatus() == PlanStepStatus.FAILED) {
                skipDownstream(orderedSteps, i + 1);
                task = tasks.findById(task.getTaskId()).orElseThrow();
                return result(task, orderedSteps, reportId, blockers);
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

    private void applyReviewGate(Task task, ManualReviewGateResult reviewGate) {
        var metadata = task.getMetadata() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<>(task.getMetadata());
        metadata.put("selectedCaseIds", reviewGate.promotedCaseIds());
        metadata.put("promotedCaseIds", reviewGate.promotedCaseIds());
        metadata.put("discardedDraftIds", reviewGate.discardedDraftIds());
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
