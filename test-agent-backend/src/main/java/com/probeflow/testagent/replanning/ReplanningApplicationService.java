package com.probeflow.testagent.replanning;

import com.probeflow.testagent.controlledplanner.ControlledPlannerService;
import com.probeflow.testagent.controlledplanner.HumanInputField;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerConstraint;
import com.probeflow.testagent.controlledplanner.PlannerInput;
import com.probeflow.testagent.controlledplanner.PlannerInputFactory;
import com.probeflow.testagent.controlledplanner.PlannerInputRequest;
import com.probeflow.testagent.controlledplanner.PlannerTaskState;
import com.probeflow.testagent.controlledplanner.RequiredHumanInput;
import com.probeflow.testagent.policyvalidator.PolicyValidationRequest;
import com.probeflow.testagent.policyvalidator.PolicyValidationResult;
import com.probeflow.testagent.policyvalidator.PolicyValidatorService;
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
    private final ControlledPlannerService controlledPlanner;
    private final PolicyValidatorService policyValidator;

    public ReplanningApplicationService(
        TaskRepository tasks,
        PlanStepRepository planSteps,
        PlannerInputFactory plannerInputFactory,
        ControlledPlannerService controlledPlanner,
        PolicyValidatorService policyValidator
    ) {
        this.tasks = tasks;
        this.planSteps = planSteps;
        this.plannerInputFactory = plannerInputFactory;
        this.controlledPlanner = controlledPlanner;
        this.policyValidator = policyValidator;
    }

    @Transactional
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
        var decision = controlledPlanner.plan(plannerInput);
        var validation = policyValidator.validate(new PolicyValidationRequest(decision, plannerInput, request.policy()));
        if (validation.blocked()) {
            return ReplanningResult.rejectedByPolicy(
                request.trigger(),
                decisionAuditSummary(decision, plannerInput),
                validation.auditSummary(),
                validation.blockers()
            );
        }
        if (validation.requiresHumanConfirmation()) {
            return pauseForHuman(request, decision, validation, plannerInput);
        }
        return ReplanningResult.noop(
            request.trigger(),
            "Policy allowed the planner decision and the deterministic template plan remains unchanged.",
            decisionAuditSummary(decision, plannerInput),
            validation.auditSummary()
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

    private ReplanningResult pauseForHuman(
        ReplanningRequest request,
        PlanDecision decision,
        PolicyValidationResult validation,
        PlannerInput plannerInput
    ) {
        var task = tasks.findById(request.taskId()).orElseThrow();
        var metadata = task.getMetadata() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<>(task.getMetadata());
        var requiredHumanInput = humanInputSummary(decision.requiredHumanInput());
        metadata.put("requiredHumanInput", requiredHumanInput);
        metadata.put("lastReplanning", Map.of(
            "trigger", request.trigger().name(),
            "decisionId", decision.decisionId(),
            "policyStatus", validation.status().name(),
            "policyReasonCode", validation.reasonCode().name(),
            "status", ReplanningStatus.WAITING_FOR_HUMAN.name()
        ));
        task.setMetadata(metadata);
        task.setStatus(TaskStatus.WAITING_FOR_REVIEW);
        tasks.save(task);

        var mutationSummary = new LinkedHashMap<String, Object>();
        mutationSummary.put("mutationApplied", true);
        mutationSummary.put("taskStatus", TaskStatus.WAITING_FOR_REVIEW.name());
        mutationSummary.put("requiredHumanInput", requiredHumanInput);
        mutationSummary.put("reason", "Task paused for human confirmation; no PlanStep was inserted, deleted or reordered.");

        var blockers = validation.blockers().isEmpty()
            ? List.of(validation.message())
            : validation.blockers();
        return ReplanningResult.waitingForHuman(
            request.trigger(),
            decisionAuditSummary(decision, plannerInput),
            validation.auditSummary(),
            mutationSummary,
            blockers
        );
    }

    private Map<String, Object> decisionAuditSummary(PlanDecision decision, PlannerInput plannerInput) {
        return merge(
            Map.of("plannerCalled", true),
            decision.auditSummary(),
            plannerInputSummary(plannerInput)
        );
    }

    private Map<String, Object> humanInputSummary(RequiredHumanInput input) {
        if (input == null) {
            return Map.of();
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("reason", input.reason());
        summary.put("question", input.question());
        summary.put("blocking", input.blocking());
        summary.put("inputSchema", input.inputSchema().stream().map(this::humanInputFieldSummary).toList());
        return Map.copyOf(summary);
    }

    private Map<String, Object> humanInputFieldSummary(HumanInputField field) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("name", field.name());
        summary.put("type", field.type());
        summary.put("description", field.description());
        summary.put("required", field.required());
        summary.entrySet().removeIf(entry -> entry.getValue() == null);
        return Map.copyOf(summary);
    }

    @SafeVarargs
    private final Map<String, Object> merge(Map<String, Object>... summaries) {
        var merged = new LinkedHashMap<String, Object>();
        for (var summary : summaries) {
            if (summary != null) {
                merged.putAll(summary);
            }
        }
        merged.entrySet().removeIf(entry -> entry.getValue() == null);
        return Map.copyOf(merged);
    }
}
