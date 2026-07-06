package com.probeflow.testagent.replanning;

import com.probeflow.testagent.controlledplanner.PlannerConstraint;
import com.probeflow.testagent.controlledplanner.PlannerInput;
import com.probeflow.testagent.controlledplanner.PlannerInputFactory;
import com.probeflow.testagent.controlledplanner.PlannerInputRequest;
import com.probeflow.testagent.controlledplanner.PlannerTaskState;
import com.probeflow.testagent.task.PlanStep;
import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReplanningApplicationService {

    private final TaskRepository tasks;
    private final PlanStepRepository planSteps;
    private final PlannerInputFactory plannerInputFactory;

    public ReplanningApplicationService(
        TaskRepository tasks,
        PlanStepRepository planSteps,
        PlannerInputFactory plannerInputFactory
    ) {
        this.tasks = tasks;
        this.planSteps = planSteps;
        this.plannerInputFactory = plannerInputFactory;
    }

    @Transactional(readOnly = true)
    public ReplanningResult replan(ReplanningRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Replanning request is required");
        }
        var task = tasks.findById(request.taskId());
        if (task.isEmpty()) {
            return ReplanningResult.failed(request.trigger(), List.of("Task not found: " + request.taskId()));
        }
        if (task.get().getStatus() == TaskStatus.COMPLETED) {
            return ReplanningResult.notTriggerable(request.trigger(), List.of("Completed task cannot be replanned"));
        }
        if (task.get().getStatus() == TaskStatus.CANCELLED) {
            return ReplanningResult.notTriggerable(request.trigger(), List.of("Cancelled task cannot be replanned"));
        }
        var plannerInput = buildPlannerInput(request);
        return ReplanningResult.noop(
            request.trigger(),
            "Recovery planner input was built and the plan was left unchanged.",
            plannerInputSummary(plannerInput)
        );
    }

    PlannerInput buildPlannerInput(ReplanningRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Replanning request is required");
        }
        var task = tasks.findById(request.taskId())
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + request.taskId()));
        var orderedSteps = planSteps.findByTaskIdOrderByStepOrderAsc(task.getTaskId());
        var sourceStep = sourceStep(request, orderedSteps);
        var constraints = new ArrayList<>(request.constraints());
        constraints.add(PlannerConstraint.of("REPLANNING_TRIGGER", request.trigger().name()));
        if (sourceStep != null) {
            constraints.add(PlannerConstraint.of("REPLANNING_SOURCE_STEP", sourceStep.getStepId()));
        }

        return plannerInputFactory.build(new PlannerInputRequest(
            PlannerTaskState.of(
                task.getTaskId(),
                task.getTaskType() == null ? null : task.getTaskType().name(),
                task.getStatus().name(),
                sourceStep == null ? null : sourceStep.getStepId(),
                remainingStepTypes(orderedSteps, sourceStep)
            ),
            request.policy(),
            request.stepOutcome(),
            sourceStep,
            request.contextSummary(),
            constraints
        ));
    }

    private PlanStep sourceStep(ReplanningRequest request, List<PlanStep> orderedSteps) {
        if (request.sourceStepId() != null) {
            return orderedSteps.stream()
                .filter(step -> request.sourceStepId().equals(step.getStepId()))
                .findFirst()
                .orElse(null);
        }
        return orderedSteps.stream()
            .filter(step -> step.getStepStatus() != PlanStepStatus.SUCCESS && step.getStepStatus() != PlanStepStatus.SKIPPED)
            .findFirst()
            .orElse(null);
    }

    private List<String> remainingStepTypes(List<PlanStep> orderedSteps, PlanStep sourceStep) {
        var sourceOrder = sourceStep == null ? Integer.MIN_VALUE : sourceStep.getStepOrder();
        return orderedSteps.stream()
            .filter(step -> step.getStepOrder() == null || step.getStepOrder() >= sourceOrder)
            .filter(step -> step.getStepStatus() != PlanStepStatus.SUCCESS && step.getStepStatus() != PlanStepStatus.SKIPPED)
            .map(PlanStep::getStepType)
            .filter(stepType -> stepType != null)
            .map(Enum::name)
            .toList();
    }

    private Map<String, Object> plannerInputSummary(PlannerInput plannerInput) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("plannerInputTraceId", plannerInput.planningTraceId());
        summary.put("taskStatus", plannerInput.taskState().taskStatus());
        summary.put("currentPhase", plannerInput.currentPhase().name());
        summary.put("workflowMode", plannerInput.workflowMode().name());
        summary.put("sourceStepId", plannerInput.lastStepOutcome().sourceStepId());
        summary.put("sourceStepType", plannerInput.lastStepOutcome().sourceStepType());
        summary.put("lastStepStatus", plannerInput.lastStepOutcome().stepStatus());
        summary.put("blockers", plannerInput.lastStepOutcome().blockers());
        summary.put("resultRefs", plannerInput.lastStepOutcome().resultRefs());
        summary.put("availableToolCount", plannerInput.availableTools().size());
        summary.entrySet().removeIf(entry -> entry.getValue() == null);
        return Map.copyOf(summary);
    }
}
