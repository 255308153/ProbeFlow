package com.probeflow.testagent.replanning;

import com.probeflow.testagent.controlledplanner.ControlledPlannerService;
import com.probeflow.testagent.controlledplanner.HumanInputField;
import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerAction;
import com.probeflow.testagent.controlledplanner.PlannerConstraint;
import com.probeflow.testagent.controlledplanner.PlannerInput;
import com.probeflow.testagent.controlledplanner.PlannerInputFactory;
import com.probeflow.testagent.controlledplanner.PlannerInputRequest;
import com.probeflow.testagent.controlledplanner.PlannerTaskState;
import com.probeflow.testagent.controlledplanner.ProposedPlanStep;
import com.probeflow.testagent.controlledplanner.RequiredHumanInput;
import com.probeflow.testagent.policyvalidator.PolicyValidationRequest;
import com.probeflow.testagent.policyvalidator.PolicyValidationResult;
import com.probeflow.testagent.policyvalidator.PolicyValidatorService;
import com.probeflow.testagent.task.PlanStep;
import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.PlanStepType;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        var validation = policyValidator.validate(new PolicyValidationRequest(
            decision,
            plannerInput,
            request.policy(),
            proposedToolInput(decision, request, plannerInput),
            Set.of()
        ));
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
        if (decision.action() == PlannerAction.INSERT_STEP) {
            return applyInsertStep(request, decision, validation, plannerInput);
        }
        if (decision.action() == PlannerAction.REPLAN) {
            return applyReplan(request, decision, validation, plannerInput);
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
        var sourceOrder = sourceStep == null || sourceStep.getStepOrder() == null
            ? Integer.MIN_VALUE
            : sourceStep.getStepOrder();
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

    private Map<String, Object> proposedToolInput(
        PlanDecision decision,
        ReplanningRequest request,
        PlannerInput plannerInput
    ) {
        var proposed = decision.proposedPlanStep();
        if (proposed == null || proposed.proposedToolName() == null) {
            return Map.of();
        }
        var input = new LinkedHashMap<String, Object>();
        switch (proposed.proposedToolName()) {
            case "knowledge.retrieve-context" -> {
                input.put("taskId", request.taskId());
                input.put("query", recoveryQuery(plannerInput));
            }
            case "failure.analyze-execution" -> {
                input.put("taskId", request.taskId());
                input.put("executionRecordId", executionRecordId(plannerInput));
            }
            case "testcase.generate-drafts" -> {
                input.put("taskId", request.taskId());
                input.put("apiSpecId", apiSpecId(plannerInput));
                input.put("generationMode", "BATCH");
            }
            default -> {
            }
        }
        input.entrySet().removeIf(entry -> entry.getValue() == null);
        return Map.copyOf(input);
    }

    private String recoveryQuery(PlannerInput plannerInput) {
        if (!plannerInput.lastStepOutcome().blockers().isEmpty()) {
            return String.join("; ", plannerInput.lastStepOutcome().blockers());
        }
        if (!plannerInput.lastStepOutcome().summary().isBlank()) {
            return plannerInput.lastStepOutcome().summary();
        }
        return "replanning recovery context";
    }

    private String executionRecordId(PlannerInput plannerInput) {
        return plannerInput.lastStepOutcome().resultRefs().stream()
            .filter(ref -> ref.toLowerCase().contains("executionrecord") || ref.toLowerCase().startsWith("exec"))
            .findFirst()
            .map(this::valueAfterSeparator)
            .orElse(null);
    }

    private String apiSpecId(PlannerInput plannerInput) {
        return plannerInput.lastStepOutcome().resultRefs().stream()
            .filter(ref -> ref.toLowerCase().contains("apispec") || ref.toLowerCase().startsWith("api"))
            .findFirst()
            .map(this::valueAfterSeparator)
            .orElse(null);
    }

    private String valueAfterSeparator(String value) {
        if (value == null) {
            return null;
        }
        var index = Math.max(value.lastIndexOf(':'), value.lastIndexOf('/'));
        if (index < 0 || index + 1 >= value.length()) {
            return value.trim();
        }
        return value.substring(index + 1).trim();
    }

    private ReplanningResult applyInsertStep(
        ReplanningRequest request,
        PlanDecision decision,
        PolicyValidationResult validation,
        PlannerInput plannerInput
    ) {
        var proposed = decision.proposedPlanStep();
        var orderedSteps = planSteps.findByTaskIdOrderByStepOrderAsc(request.taskId());
        var sourceStep = sourceStep(request, orderedSteps);
        var saved = appendRecoveryStep(request, decision, orderedSteps, sourceStep, proposed);
        if (saved == null) {
            return ReplanningResult.failed(request.trigger(), List.of("Planner proposed an illegal PlanStepType."));
        }
        var mutationSummary = new LinkedHashMap<String, Object>();
        mutationSummary.put("mutationApplied", true);
        mutationSummary.put("mutationType", PlannerAction.INSERT_STEP.name());
        mutationSummary.put("insertedStepId", saved.getStepId());
        mutationSummary.put("insertedStepType", saved.getStepType().name());
        mutationSummary.put("insertedStepOrder", saved.getStepOrder());
        mutationSummary.put("insertedStepStatus", saved.getStepStatus().name());
        mutationSummary.put("trigger", request.trigger().name());
        mutationSummary.put("decisionId", decision.decisionId());
        mutationSummary.put("sourceStepId", sourceStep == null ? null : sourceStep.getStepId());
        mutationSummary.put("inputRef", saved.getInputRef());
        mutationSummary.entrySet().removeIf(entry -> entry.getValue() == null);

        return ReplanningResult.applied(
            request.trigger(),
            List.of(),
            decisionAuditSummary(decision, plannerInput),
            validation.auditSummary(),
            mutationSummary,
            List.of(saved.getStepId()),
            List.of()
        );
    }

    private ReplanningResult applyReplan(
        ReplanningRequest request,
        PlanDecision decision,
        PolicyValidationResult validation,
        PlannerInput plannerInput
    ) {
        var orderedSteps = planSteps.findByTaskIdOrderByStepOrderAsc(request.taskId());
        var sourceStep = sourceStep(request, orderedSteps);
        var skipped = skipPendingDownstreamSteps(orderedSteps, sourceStep);
        var safeStop = request.trigger() == ReplanningTrigger.FAILURE_ANALYSIS_HIGH_RISK;
        var inserted = safeStop
            ? null
            : appendRecoveryStep(request, decision, orderedSteps, sourceStep, recoveryStepForReplan(request.trigger()));

        var mutationSummary = new LinkedHashMap<String, Object>();
        mutationSummary.put("mutationApplied", true);
        mutationSummary.put("mutationType", PlannerAction.REPLAN.name());
        mutationSummary.put("safeStop", safeStop);
        mutationSummary.put("skippedStepIds", skipped.stream().map(PlanStep::getStepId).toList());
        mutationSummary.put("insertedStepIds", inserted == null ? List.of() : List.of(inserted.getStepId()));
        mutationSummary.put("sourceStepId", sourceStep == null ? null : sourceStep.getStepId());
        mutationSummary.put("trigger", request.trigger().name());
        mutationSummary.put("decisionId", decision.decisionId());
        mutationSummary.put("reason", safeStop
            ? "High-risk failure analysis trigger truncated pending downstream work and performed a safe stop without adding a recovery step."
            : "Pending downstream steps were skipped before appending a controlled recovery step.");
        if (inserted != null) {
            mutationSummary.put("insertedStepType", inserted.getStepType().name());
            mutationSummary.put("insertedStepOrder", inserted.getStepOrder());
        }
        mutationSummary.entrySet().removeIf(entry -> entry.getValue() == null);

        return ReplanningResult.applied(
            request.trigger(),
            decision.blockers(),
            decisionAuditSummary(decision, plannerInput),
            validation.auditSummary(),
            mutationSummary,
            inserted == null ? List.of() : List.of(inserted.getStepId()),
            skipped.stream().map(PlanStep::getStepId).toList()
        );
    }

    private List<PlanStep> skipPendingDownstreamSteps(List<PlanStep> orderedSteps, PlanStep sourceStep) {
        var sourceOrder = sourceStep == null || sourceStep.getStepOrder() == null
            ? Integer.MIN_VALUE
            : sourceStep.getStepOrder();
        var skipped = orderedSteps.stream()
            .filter(step -> step.getStepOrder() != null && step.getStepOrder() > sourceOrder)
            .filter(step -> step.getStepStatus() == PlanStepStatus.PENDING)
            .toList();
        skipped.forEach(step -> step.setStepStatus(PlanStepStatus.SKIPPED));
        planSteps.saveAll(skipped);
        return skipped;
    }

    private ProposedPlanStep recoveryStepForReplan(ReplanningTrigger trigger) {
        if (trigger == ReplanningTrigger.PLAN_STEP_FAILED) {
            return ProposedPlanStep.of(
                "ANALYZE_FAILURE",
                "Analyze failed step",
                "Inspect the failure before rebuilding downstream work.",
                "failure.analyze-execution"
            );
        }
        if (trigger == ReplanningTrigger.CONTEXT_MISSING) {
            return ProposedPlanStep.of(
                "RETRIEVE_KNOWLEDGE",
                "Retrieve missing context",
                "Collect citations before rebuilding downstream work.",
                "knowledge.retrieve-context"
            );
        }
        return null;
    }

    private PlanStep appendRecoveryStep(
        ReplanningRequest request,
        PlanDecision decision,
        List<PlanStep> orderedSteps,
        PlanStep sourceStep,
        ProposedPlanStep proposed
    ) {
        var stepType = parsePlanStepType(proposed);
        if (stepType == null) {
            return null;
        }
        var inserted = new PlanStep();
        inserted.setTaskId(request.taskId());
        inserted.setStepType(stepType);
        inserted.setStepStatus(PlanStepStatus.PENDING);
        inserted.setStepOrder(nextRecoveryStepOrder(orderedSteps));
        inserted.setGoal(recoveryGoal(proposed));
        inserted.setInputRef(recoveryInputRef(request.trigger(), decision, sourceStep, proposed));
        inserted.setRetryCount(0);
        return planSteps.save(inserted);
    }

    private PlanStepType parsePlanStepType(ProposedPlanStep proposed) {
        if (proposed == null) {
            return null;
        }
        try {
            return PlanStepType.valueOf(proposed.stepType());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private int nextRecoveryStepOrder(List<PlanStep> orderedSteps) {
        return orderedSteps.stream()
            .map(PlanStep::getStepOrder)
            .filter(order -> order != null)
            .max(Integer::compareTo)
            .orElse(0) + 10;
    }

    private String recoveryGoal(ProposedPlanStep proposed) {
        if (proposed.description() == null) {
            return proposed.title();
        }
        return proposed.title() + " - " + proposed.description();
    }

    private String recoveryInputRef(
        ReplanningTrigger trigger,
        PlanDecision decision,
        PlanStep sourceStep,
        ProposedPlanStep proposed
    ) {
        return "replanning:"
            + "trigger=" + trigger.name()
            + ";decision=" + decision.decisionId()
            + ";sourceStep=" + (sourceStep == null ? "" : sourceStep.getStepId())
            + ";tool=" + (proposed.proposedToolName() == null ? "" : proposed.proposedToolName());
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
