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
import com.probeflow.testagent.humanintheloop.HumanInTheLoopApplicationService;
import com.probeflow.testagent.humanintheloop.HumanRequestType;
import com.probeflow.testagent.humanintheloop.HumanReviewRequestCreateRequest;
import com.probeflow.testagent.humanintheloop.HumanReviewRequestCreationResult;
import com.probeflow.testagent.orchestration.ManualReviewGate;
import com.probeflow.testagent.orchestration.ManualReviewGateResult;
import com.probeflow.testagent.policyvalidator.PolicyValidationRequest;
import com.probeflow.testagent.policyvalidator.PolicyValidationResult;
import com.probeflow.testagent.policyvalidator.PolicyValidationReasonCode;
import com.probeflow.testagent.policyvalidator.PolicyValidatorService;
import com.probeflow.testagent.task.PlanStep;
import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.PlanStepType;
import com.probeflow.testagent.task.Task;
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

    private static final int MAX_TASK_REPLANNING_ATTEMPTS = 5;
    private static final int MAX_TRIGGER_REPLANNING_ATTEMPTS = 2;
    private static final int MAX_SOURCE_STEP_RETRY_COUNT = 3;
    private static final String ATTEMPT_COUNT_KEY = "replanningAttemptCount";
    private static final String TRIGGER_ATTEMPTS_KEY = "replanningTriggerAttempts";
    private static final String ATTEMPT_RECORDS_KEY = "replanningAttemptRecords";

    private final TaskRepository tasks;
    private final PlanStepRepository planSteps;
    private final PlannerInputFactory plannerInputFactory;
    private final ControlledPlannerService controlledPlanner;
    private final PolicyValidatorService policyValidator;
    private final ManualReviewGate manualReviewGate;
    private final HumanInTheLoopApplicationService humanInTheLoop;

    private record AttemptScope(
        String attemptKey,
        String triggerAttemptKey,
        int totalAttemptCount,
        int triggerAttemptCount
    ) {

        Map<String, Object> auditSummary() {
            return Map.of(
                "attemptKey", attemptKey,
                "attemptCount", totalAttemptCount,
                "triggerAttemptCount", triggerAttemptCount,
                "maxTaskAttempts", MAX_TASK_REPLANNING_ATTEMPTS,
                "maxTriggerAttempts", MAX_TRIGGER_REPLANNING_ATTEMPTS
            );
        }
    }

    private record AttemptPreparation(AttemptScope attempt, ReplanningResult result) {
    }

    public ReplanningApplicationService(
        TaskRepository tasks,
        PlanStepRepository planSteps,
        PlannerInputFactory plannerInputFactory,
        ControlledPlannerService controlledPlanner,
        PolicyValidatorService policyValidator,
        ManualReviewGate manualReviewGate,
        HumanInTheLoopApplicationService humanInTheLoop
    ) {
        this.tasks = tasks;
        this.planSteps = planSteps;
        this.plannerInputFactory = plannerInputFactory;
        this.controlledPlanner = controlledPlanner;
        this.policyValidator = policyValidator;
        this.manualReviewGate = manualReviewGate;
        this.humanInTheLoop = humanInTheLoop;
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
        if (request.trigger() == ReplanningTrigger.REVIEW_COMPLETED) {
            return applyReviewCompleted(request, task.get());
        }
        var orderedSteps = planSteps.findByTaskIdOrderByStepOrderAsc(task.get().getTaskId());
        var sourceStep = sourceStep(request, orderedSteps);
        var terminalGuard = terminalGuard(request, task.get(), sourceStep);
        if (terminalGuard != null) {
            return terminalGuard;
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
        var attemptPreparation = prepareAttempt(request, task.get(), sourceStep, decision, plannerInput, validation);
        if (attemptPreparation.result() != null) {
            return attemptPreparation.result();
        }
        var attempt = attemptPreparation.attempt();
        if (validation.requiresHumanConfirmation()) {
            return pauseForHuman(request, decision, validation, plannerInput, attempt);
        }
        if (decision.action() == PlannerAction.INSERT_STEP) {
            return applyInsertStep(request, decision, validation, plannerInput, attempt);
        }
        if (decision.action() == PlannerAction.REPLAN) {
            return applyReplan(request, decision, validation, plannerInput, attempt);
        }
        var result = ReplanningResult.noop(
            request.trigger(),
            "Policy allowed the planner decision and the deterministic template plan remains unchanged.",
            merge(decisionAuditSummary(decision, plannerInput), attempt.auditSummary()),
            validation.auditSummary()
        );
        recordCompletedAttempt(request.taskId(), attempt, result, decision.action().name());
        return result;
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

    private ReplanningResult terminalGuard(ReplanningRequest request, Task task, PlanStep sourceStep) {
        if (task.getStatus() == TaskStatus.FAILED && !explicitRecoveryTrigger(request.trigger())) {
            return ReplanningResult.notTriggerable(
                request.trigger(),
                List.of("Failed task requires an explicit recovery trigger before replanning.")
            );
        }
        var retryCount = retryCount(sourceStep);
        if (retryCount >= MAX_SOURCE_STEP_RETRY_COUNT) {
            var mutationSummary = new LinkedHashMap<String, Object>();
            mutationSummary.put("mutationApplied", false);
            mutationSummary.put("reason", "Source step retry count reached the replanning retry limit.");
            mutationSummary.put("sourceStepId", sourceStep.getStepId());
            mutationSummary.put("retryCount", retryCount);
            mutationSummary.put("maxRetryCount", MAX_SOURCE_STEP_RETRY_COUNT);
            return new ReplanningResult(
                ReplanningStatus.NOT_TRIGGERABLE,
                request.trigger(),
                List.of("Source step retry count reached limit: " + retryCount),
                Map.of("plannerCalled", false, "reason", "Retry guard stopped replanning before planner execution."),
                Map.of("policyValidated", false, "reason", "Policy validation was not reached."),
                mutationSummary,
                List.of(),
                List.of()
            );
        }
        return null;
    }

    private boolean explicitRecoveryTrigger(ReplanningTrigger trigger) {
        return trigger == ReplanningTrigger.PLAN_STEP_FAILED
            || trigger == ReplanningTrigger.FAILURE_ANALYSIS_HIGH_RISK
            || trigger == ReplanningTrigger.HUMAN_INPUT_REQUIRED
            || trigger == ReplanningTrigger.REVIEW_COMPLETED;
    }

    private int retryCount(PlanStep sourceStep) {
        return sourceStep == null || sourceStep.getRetryCount() == null ? 0 : sourceStep.getRetryCount();
    }

    private AttemptPreparation prepareAttempt(
        ReplanningRequest request,
        Task task,
        PlanStep sourceStep,
        PlanDecision decision,
        PlannerInput plannerInput,
        PolicyValidationResult validation
    ) {
        var metadata = mutableMetadata(task.getMetadata());
        var attemptKey = attemptKey(request, sourceStep, decision);
        var records = mutableMetadataMap(metadata.get(ATTEMPT_RECORDS_KEY));
        var existingRecord = metadataMap(records.get(attemptKey));
        if (!existingRecord.isEmpty()) {
            return new AttemptPreparation(null, idempotentResult(request, decision, plannerInput, validation, existingRecord));
        }

        var totalAttemptCount = metadataInt(metadata.get(ATTEMPT_COUNT_KEY));
        var triggerAttempts = mutableMetadataMap(metadata.get(TRIGGER_ATTEMPTS_KEY));
        var triggerAttemptKey = triggerAttemptKey(request, sourceStep);
        var triggerAttemptCount = metadataInt(triggerAttempts.get(triggerAttemptKey));
        if (totalAttemptCount >= MAX_TASK_REPLANNING_ATTEMPTS) {
            return new AttemptPreparation(
                null,
                attemptLimitResult(
                    request,
                    decision,
                    plannerInput,
                    validation,
                    "Task replanning attempt limit reached.",
                    totalAttemptCount,
                    triggerAttemptCount,
                    triggerAttemptKey
                )
            );
        }
        if (triggerAttemptCount >= MAX_TRIGGER_REPLANNING_ATTEMPTS) {
            return new AttemptPreparation(
                null,
                attemptLimitResult(
                    request,
                    decision,
                    plannerInput,
                    validation,
                    "Trigger replanning attempt limit reached.",
                    totalAttemptCount,
                    triggerAttemptCount,
                    triggerAttemptKey
                )
            );
        }

        var attempt = new AttemptScope(attemptKey, triggerAttemptKey, totalAttemptCount + 1, triggerAttemptCount + 1);
        triggerAttempts.put(triggerAttemptKey, attempt.triggerAttemptCount());
        metadata.put(ATTEMPT_COUNT_KEY, attempt.totalAttemptCount());
        metadata.put(TRIGGER_ATTEMPTS_KEY, triggerAttempts);
        metadata.put("lastReplanningAttempt", Map.of(
            "attemptKey", attempt.attemptKey(),
            "triggerAttemptKey", attempt.triggerAttemptKey(),
            "attemptCount", attempt.totalAttemptCount(),
            "triggerAttemptCount", attempt.triggerAttemptCount(),
            "trigger", request.trigger().name(),
            "decisionId", decision.decisionId()
        ));
        task.setMetadata(metadata);
        tasks.save(task);
        return new AttemptPreparation(attempt, null);
    }

    private ReplanningResult idempotentResult(
        ReplanningRequest request,
        PlanDecision decision,
        PlannerInput plannerInput,
        PolicyValidationResult validation,
        Map<String, Object> existingRecord
    ) {
        var insertedStepIds = metadataStringList(existingRecord.get("insertedStepIds"));
        var skippedStepIds = metadataStringList(existingRecord.get("skippedStepIds"));
        var mutationSummary = new LinkedHashMap<String, Object>();
        mutationSummary.put("mutationApplied", false);
        mutationSummary.put("idempotent", true);
        mutationSummary.put("reason", "Duplicate replanning trigger ignored; previous controlled mutation is reused.");
        mutationSummary.put("attemptKey", existingRecord.get("attemptKey"));
        mutationSummary.put("attemptCount", existingRecord.get("attemptCount"));
        mutationSummary.put("triggerAttemptCount", existingRecord.get("triggerAttemptCount"));
        mutationSummary.put("originalStatus", existingRecord.get("status"));
        mutationSummary.put("originalMutationType", existingRecord.get("mutationType"));
        mutationSummary.put("insertedStepIds", insertedStepIds);
        mutationSummary.put("skippedStepIds", skippedStepIds);
        mutationSummary.entrySet().removeIf(entry -> entry.getValue() == null);
        var attemptSummary = new LinkedHashMap<String, Object>();
        attemptSummary.put("idempotent", true);
        attemptSummary.put("attemptKey", existingRecord.get("attemptKey"));
        attemptSummary.put("attemptCount", existingRecord.get("attemptCount"));
        attemptSummary.put("triggerAttemptCount", existingRecord.get("triggerAttemptCount"));
        attemptSummary.entrySet().removeIf(entry -> entry.getValue() == null);

        return new ReplanningResult(
            ReplanningStatus.NOOP,
            request.trigger(),
            List.of("Duplicate replanning trigger ignored."),
            merge(decisionAuditSummary(decision, plannerInput), attemptSummary),
            validation.auditSummary(),
            mutationSummary,
            insertedStepIds,
            skippedStepIds
        );
    }

    private ReplanningResult attemptLimitResult(
        ReplanningRequest request,
        PlanDecision decision,
        PlannerInput plannerInput,
        PolicyValidationResult validation,
        String reason,
        int totalAttemptCount,
        int triggerAttemptCount,
        String triggerAttemptKey
    ) {
        var mutationSummary = new LinkedHashMap<String, Object>();
        mutationSummary.put("mutationApplied", false);
        mutationSummary.put("reason", reason);
        mutationSummary.put("attemptCount", totalAttemptCount);
        mutationSummary.put("triggerAttemptCount", triggerAttemptCount);
        mutationSummary.put("triggerAttemptKey", triggerAttemptKey);
        mutationSummary.put("maxTaskAttempts", MAX_TASK_REPLANNING_ATTEMPTS);
        mutationSummary.put("maxTriggerAttempts", MAX_TRIGGER_REPLANNING_ATTEMPTS);
        return new ReplanningResult(
            ReplanningStatus.NOT_TRIGGERABLE,
            request.trigger(),
            List.of(reason),
            merge(decisionAuditSummary(decision, plannerInput), Map.of(
                "attemptCount", totalAttemptCount,
                "triggerAttemptCount", triggerAttemptCount
            )),
            validation.auditSummary(),
            mutationSummary,
            List.of(),
            List.of()
        );
    }

    private void recordCompletedAttempt(
        String taskId,
        AttemptScope attempt,
        ReplanningResult result,
        String mutationType
    ) {
        if (attempt == null) {
            return;
        }
        var task = tasks.findById(taskId).orElseThrow();
        var metadata = mutableMetadata(task.getMetadata());
        var records = mutableMetadataMap(metadata.get(ATTEMPT_RECORDS_KEY));
        records.put(attempt.attemptKey(), Map.of(
            "attemptKey", attempt.attemptKey(),
            "triggerAttemptKey", attempt.triggerAttemptKey(),
            "attemptCount", attempt.totalAttemptCount(),
            "triggerAttemptCount", attempt.triggerAttemptCount(),
            "status", result.status().name(),
            "mutationType", mutationType,
            "insertedStepIds", result.insertedStepIds(),
            "skippedStepIds", result.skippedStepIds()
        ));
        metadata.put(ATTEMPT_RECORDS_KEY, records);
        metadata.put(ATTEMPT_COUNT_KEY, attempt.totalAttemptCount());
        var triggerAttempts = mutableMetadataMap(metadata.get(TRIGGER_ATTEMPTS_KEY));
        triggerAttempts.put(attempt.triggerAttemptKey(), attempt.triggerAttemptCount());
        metadata.put(TRIGGER_ATTEMPTS_KEY, triggerAttempts);
        task.setMetadata(metadata);
        tasks.save(task);
    }

    private void rollbackAttempt(String taskId, AttemptScope attempt) {
        if (attempt == null) {
            return;
        }
        var task = tasks.findById(taskId).orElseThrow();
        var metadata = mutableMetadata(task.getMetadata());
        metadata.put(ATTEMPT_COUNT_KEY, Math.max(0, attempt.totalAttemptCount() - 1));
        var triggerAttempts = mutableMetadataMap(metadata.get(TRIGGER_ATTEMPTS_KEY));
        var previousTriggerCount = Math.max(0, attempt.triggerAttemptCount() - 1);
        if (previousTriggerCount == 0) {
            triggerAttempts.remove(attempt.triggerAttemptKey());
        } else {
            triggerAttempts.put(attempt.triggerAttemptKey(), previousTriggerCount);
        }
        metadata.put(TRIGGER_ATTEMPTS_KEY, triggerAttempts);
        var lastAttempt = metadataMap(metadata.get("lastReplanningAttempt"));
        if (attempt.attemptKey().equals(lastAttempt.get("attemptKey"))) {
            metadata.remove("lastReplanningAttempt");
        }
        task.setMetadata(metadata);
        tasks.save(task);
    }

    private String attemptKey(ReplanningRequest request, PlanStep sourceStep, PlanDecision decision) {
        return request.taskId()
            + "|" + request.trigger().name()
            + "|" + sourceStepId(request, sourceStep)
            + "|" + decision.decisionId();
    }

    private String triggerAttemptKey(ReplanningRequest request, PlanStep sourceStep) {
        return request.trigger().name() + "|" + sourceStepId(request, sourceStep);
    }

    private String sourceStepId(ReplanningRequest request, PlanStep sourceStep) {
        if (sourceStep != null) {
            return sourceStep.getStepId();
        }
        return request.sourceStepId() == null ? "" : request.sourceStepId();
    }

    private LinkedHashMap<String, Object> mutableMetadata(Map<String, Object> metadata) {
        return metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    private LinkedHashMap<String, Object> mutableMetadataMap(Object value) {
        return new LinkedHashMap<>(metadataMap(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, Object>();
        values.forEach((key, item) -> result.put(key == null ? "" : key.toString(), item));
        result.remove("");
        return result;
    }

    private int metadataInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private List<String> metadataStringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .map(item -> item == null ? "" : item.toString())
            .filter(item -> !item.isBlank())
            .toList();
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

    private ReplanningResult applyReviewCompleted(ReplanningRequest request, Task task) {
        if (!request.reviewCompleted()) {
            return ReplanningResult.waitingForHuman(
                request.trigger(),
                reviewCompletedDecisionSummary("Review completion flag was not provided; keeping the task paused."),
                reviewCompletedPolicySummary(),
                reviewCompletedMutationSummary(false, task.getStatus(), task.getStatus(), false, null),
                List.of("Review completion signal is required before resuming task.")
            );
        }
        if (task.getStatus() != TaskStatus.WAITING_FOR_REVIEW) {
            return ReplanningResult.noop(
                request.trigger(),
                "Review completed trigger kept the template plan unchanged because the task is not waiting for review.",
                reviewCompletedDecisionSummary("Task is not waiting for review."),
                reviewCompletedPolicySummary()
            );
        }

        var reviewGate = manualReviewGate.evaluate(task);
        if (!reviewGate.ready()) {
            var humanRequest = createDraftReviewRequest(request, task, reviewGate);
            return ReplanningResult.waitingForHuman(
                request.trigger(),
                reviewCompletedDecisionSummary("Manual review gate is still waiting for review completion."),
                reviewCompletedPolicySummary(),
                merge(
                    reviewCompletedMutationSummary(false, task.getStatus(), task.getStatus(), true, reviewGate),
                    humanRequestSummary(humanRequest)
                ),
                reviewGate.blockerDetails()
            );
        }

        var metadata = task.getMetadata() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<>(task.getMetadata());
        var resumeStatus = resumeStatus(metadata, reviewGate);
        metadata.remove("requiredHumanInput");
        if (!request.humanInput().isEmpty()) {
            metadata.put("reviewCompletedHumanInput", request.humanInput());
        }
        applyReviewGateMetadata(metadata, reviewGate);
        metadata.put("lastReplanning", Map.of(
            "trigger", request.trigger().name(),
            "reviewCompleted", true,
            "status", ReplanningStatus.APPLIED.name(),
            "taskStatus", resumeStatus.name(),
            "manualReviewGateReady", true
        ));
        task.setMetadata(metadata);
        task.setStatus(resumeStatus);
        tasks.save(task);

        return ReplanningResult.applied(
            request.trigger(),
            List.of(),
            reviewCompletedDecisionSummary("Review completed; keeping the deterministic template plan and resuming downstream steps."),
            reviewCompletedPolicySummary(),
            reviewCompletedMutationSummary(true, TaskStatus.WAITING_FOR_REVIEW, resumeStatus, true, reviewGate),
            List.of(),
            List.of()
        );
    }

    private Map<String, Object> reviewCompletedDecisionSummary(String reason) {
        return Map.of(
            "plannerCalled", false,
            "action", PlannerAction.CONTINUE.name(),
            "reason", reason
        );
    }

    private Map<String, Object> reviewCompletedPolicySummary() {
        return Map.of(
            "policyValidated", false,
            "reason", "Review completion is a deterministic resume signal and does not carry a PlannerDecision."
        );
    }

    private Map<String, Object> reviewCompletedMutationSummary(
        boolean mutationApplied,
        TaskStatus previousStatus,
        TaskStatus taskStatus,
        boolean manualReviewGateEvaluated,
        ManualReviewGateResult reviewGate
    ) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("mutationApplied", mutationApplied);
        summary.put("mutationType", "REVIEW_COMPLETED_RESUME");
        summary.put("previousTaskStatus", previousStatus.name());
        summary.put("taskStatus", taskStatus.name());
        summary.put("manualReviewGateEvaluated", manualReviewGateEvaluated);
        if (reviewGate != null) {
            summary.put("manualReviewGateReady", reviewGate.ready());
            summary.put("promotedCaseIds", reviewGate.promotedCaseIds());
            summary.put("pendingDraftIds", reviewGate.pendingDraftIds());
            summary.put("discardedDraftIds", reviewGate.discardedDraftIds());
        }
        summary.put("reason", mutationApplied
            ? "Review completed; task status resumed without inserting, deleting or reordering PlanSteps."
            : "Task remains paused and the template plan is unchanged.");
        return Map.copyOf(summary);
    }

    private void applyReviewGateMetadata(Map<String, Object> metadata, ManualReviewGateResult reviewGate) {
        metadata.put("selectedCaseIds", reviewGate.promotedCaseIds());
        metadata.put("promotedCaseIds", reviewGate.promotedCaseIds());
        metadata.put("discardedDraftIds", reviewGate.discardedDraftIds());
    }

    private TaskStatus resumeStatus(Map<String, Object> metadata, ManualReviewGateResult reviewGate) {
        if (!reviewGate.promotedCaseIds().isEmpty() || !reviewGate.discardedDraftIds().isEmpty()) {
            return TaskStatus.CASE_GENERATED;
        }
        return storedResumeStatus(metadata);
    }

    private TaskStatus storedResumeStatus(Map<String, Object> metadata) {
        var lastReplanning = metadata.get("lastReplanning");
        if (lastReplanning instanceof Map<?, ?> values) {
            var value = values.get("resumeTaskStatus");
            if (value != null) {
                try {
                    return TaskStatus.valueOf(value.toString());
                } catch (IllegalArgumentException ignored) {
                    return TaskStatus.CASE_GENERATED;
                }
            }
        }
        return TaskStatus.CASE_GENERATED;
    }

    private ReplanningResult applyInsertStep(
        ReplanningRequest request,
        PlanDecision decision,
        PolicyValidationResult validation,
        PlannerInput plannerInput,
        AttemptScope attempt
    ) {
        var proposed = decision.proposedPlanStep();
        var orderedSteps = planSteps.findByTaskIdOrderByStepOrderAsc(request.taskId());
        var sourceStep = sourceStep(request, orderedSteps);
        var saved = appendRecoveryStep(request, decision, orderedSteps, sourceStep, proposed);
        if (saved == null) {
            rollbackAttempt(request.taskId(), attempt);
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
        mutationSummary.putAll(attempt.auditSummary());
        mutationSummary.entrySet().removeIf(entry -> entry.getValue() == null);

        var result = ReplanningResult.applied(
            request.trigger(),
            List.of(),
            merge(decisionAuditSummary(decision, plannerInput), attempt.auditSummary()),
            validation.auditSummary(),
            mutationSummary,
            List.of(saved.getStepId()),
            List.of()
        );
        recordCompletedAttempt(request.taskId(), attempt, result, PlannerAction.INSERT_STEP.name());
        return result;
    }

    private ReplanningResult applyReplan(
        ReplanningRequest request,
        PlanDecision decision,
        PolicyValidationResult validation,
        PlannerInput plannerInput,
        AttemptScope attempt
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
        mutationSummary.putAll(attempt.auditSummary());
        mutationSummary.entrySet().removeIf(entry -> entry.getValue() == null);

        var result = ReplanningResult.applied(
            request.trigger(),
            decision.blockers(),
            merge(decisionAuditSummary(decision, plannerInput), attempt.auditSummary()),
            validation.auditSummary(),
            mutationSummary,
            inserted == null ? List.of() : List.of(inserted.getStepId()),
            skipped.stream().map(PlanStep::getStepId).toList()
        );
        recordCompletedAttempt(request.taskId(), attempt, result, PlannerAction.REPLAN.name());
        return result;
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
        PlannerInput plannerInput,
        AttemptScope attempt
    ) {
        var task = tasks.findById(request.taskId()).orElseThrow();
        var metadata = task.getMetadata() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<>(task.getMetadata());
        var resumeStatus = task.getStatus();
        var requiredHumanInput = humanInputSummary(decision.requiredHumanInput(), validation);
        var humanRequest = createReplanningHumanRequest(request, decision, validation, plannerInput, requiredHumanInput);
        metadata.put("requiredHumanInput", requiredHumanInput);
        metadata.put("lastReplanning", Map.of(
            "trigger", request.trigger().name(),
            "decisionId", decision.decisionId(),
            "policyStatus", validation.status().name(),
            "policyReasonCode", validation.reasonCode().name(),
            "resumeTaskStatus", resumeStatus.name(),
            "attemptKey", attempt.attemptKey(),
            "attemptCount", attempt.totalAttemptCount(),
            "triggerAttemptCount", attempt.triggerAttemptCount(),
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
        mutationSummary.putAll(humanRequestSummary(humanRequest));
        mutationSummary.putAll(attempt.auditSummary());

        var blockers = validation.blockers().isEmpty()
            ? List.of(validation.message())
            : validation.blockers();
        var result = ReplanningResult.waitingForHuman(
            request.trigger(),
            merge(decisionAuditSummary(decision, plannerInput), attempt.auditSummary()),
            validation.auditSummary(),
            mutationSummary,
            blockers
        );
        recordCompletedAttempt(request.taskId(), attempt, result, PlannerAction.WAIT_FOR_HUMAN.name());
        return result;
    }

    private HumanReviewRequestCreationResult createReplanningHumanRequest(
        ReplanningRequest request,
        PlanDecision decision,
        PolicyValidationResult validation,
        PlannerInput plannerInput,
        Map<String, Object> requiredHumanInput
    ) {
        var requestType = humanRequestType(request, validation);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("sourceTrigger", request.trigger().name());
        metadata.put("sourceStepId", request.sourceStepId());
        metadata.put("requestType", requestType.name());
        metadata.put("plannerAction", decision.action().name());
        metadata.put("decisionId", decision.decisionId());
        metadata.put("policyReason", validation.reasonCode().name());
        metadata.put("blockers", blockerSummary(request, validation));
        metadata.put("question", requiredHumanInput.get("question"));
        metadata.put("plannerInputTraceId", plannerInput.planningTraceId());
        metadata.entrySet().removeIf(entry -> entry.getValue() == null);

        var idempotencyKey = humanRequestIdempotencyKey(
            request.taskId(),
            requestType,
            request.trigger().name(),
            request.sourceStepId(),
            blockerSummary(request, validation)
        );
        return humanInTheLoop.createOrReusePendingRequest(new HumanReviewRequestCreateRequest(
            request.taskId(),
            request.sourceStepId(),
            requestType,
            waitingReason(requiredHumanInput, validation),
            requiredInputSchema(requiredHumanInput),
            decision.riskLevel(),
            request.trigger().name(),
            decision.decisionId(),
            validation.reasonCode().name(),
            metadata,
            null
        ), idempotencyKey);
    }

    private HumanReviewRequestCreationResult createDraftReviewRequest(
        ReplanningRequest request,
        Task task,
        ManualReviewGateResult reviewGate
    ) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("sourceTrigger", request.trigger().name());
        metadata.put("sourceStepId", request.sourceStepId());
        metadata.put("requestType", HumanRequestType.DRAFT_REVIEW.name());
        metadata.put("pendingDraftIds", reviewGate.pendingDraftIds());
        metadata.put("promotedCaseIds", reviewGate.promotedCaseIds());
        metadata.put("discardedDraftIds", reviewGate.discardedDraftIds());

        var idempotencyKey = humanRequestIdempotencyKey(
            task.getTaskId(),
            HumanRequestType.DRAFT_REVIEW,
            request.trigger().name(),
            request.sourceStepId(),
            reviewGate.pendingDraftIds()
        );
        return humanInTheLoop.createOrReusePendingRequest(new HumanReviewRequestCreateRequest(
            task.getTaskId(),
            request.sourceStepId(),
            HumanRequestType.DRAFT_REVIEW,
            reviewGate.blockerDetails().isEmpty()
                ? "Waiting for manual review of generated drafts."
                : String.join("; ", reviewGate.blockerDetails()),
            List.of(
                Map.of("name", "reviewDecision", "type", "string", "required", true),
                Map.of("name", "draftIds", "type", "array", "required", true)
            ),
            com.probeflow.testagent.agentpolicy.ToolRiskLevel.LOW,
            request.trigger().name(),
            null,
            "MANUAL_REVIEW_PENDING",
            metadata,
            null
        ), idempotencyKey);
    }

    private HumanRequestType humanRequestType(ReplanningRequest request, PolicyValidationResult validation) {
        if (validation.reasonCode() == PolicyValidationReasonCode.HIGH_RISK_REQUIRES_CONFIRMATION
            || validation.reasonCode() == PolicyValidationReasonCode.HUMAN_CONFIRMATION_REQUIRED) {
            return HumanRequestType.HIGH_RISK_APPROVAL;
        }
        if (request.trigger() == ReplanningTrigger.CONTEXT_MISSING) {
            return HumanRequestType.BLOCKER_RESOLUTION;
        }
        if (request.trigger() == ReplanningTrigger.EXECUTION_READINESS_MISSING) {
            return HumanRequestType.MISSING_INPUT;
        }
        return HumanRequestType.PLANNER_CLARIFICATION;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> requiredInputSchema(Map<String, Object> requiredHumanInput) {
        var schema = requiredHumanInput.get("inputSchema");
        if (!(schema instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .filter(Map.class::isInstance)
            .map(value -> new LinkedHashMap<>((Map<String, Object>) value))
            .map(Map::copyOf)
            .toList();
    }

    private String waitingReason(Map<String, Object> requiredHumanInput, PolicyValidationResult validation) {
        var reason = stringValue(requiredHumanInput.get("reason"));
        var question = stringValue(requiredHumanInput.get("question"));
        if (!reason.isBlank() && !question.isBlank()) {
            return reason + " - " + question;
        }
        if (!reason.isBlank()) {
            return reason;
        }
        if (!question.isBlank()) {
            return question;
        }
        return validation.message();
    }

    private List<String> blockerSummary(ReplanningRequest request, PolicyValidationResult validation) {
        if (request.stepOutcome() != null && !request.stepOutcome().blockerDetails().isEmpty()) {
            return request.stepOutcome().blockerDetails();
        }
        if (!validation.blockers().isEmpty()) {
            return validation.blockers();
        }
        return List.of(validation.message());
    }

    private String humanRequestIdempotencyKey(
        String taskId,
        HumanRequestType requestType,
        String trigger,
        String sourceStepId,
        List<String> blockers
    ) {
        return taskId
            + "|" + requestType.name()
            + "|" + trigger
            + "|" + (sourceStepId == null ? "" : sourceStepId)
            + "|" + String.join(";", blockers == null ? List.of() : blockers);
    }

    private Map<String, Object> humanRequestSummary(HumanReviewRequestCreationResult result) {
        if (result == null || result.request() == null) {
            return Map.of("humanReviewRequestCreated", false);
        }
        return Map.of(
            "humanReviewRequestCreated", result.status().name(),
            "humanReviewRequestId", result.request().getRequestId(),
            "humanReviewRequestType", result.request().getRequestType().name()
        );
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private Map<String, Object> decisionAuditSummary(PlanDecision decision, PlannerInput plannerInput) {
        return merge(
            Map.of("plannerCalled", true),
            decision.auditSummary(),
            plannerInputSummary(plannerInput)
        );
    }

    private Map<String, Object> humanInputSummary(RequiredHumanInput input, PolicyValidationResult validation) {
        if (input == null) {
            return policyConfirmationInputSummary(validation);
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("reason", input.reason());
        summary.put("question", input.question());
        summary.put("blocking", input.blocking());
        summary.put("inputSchema", input.inputSchema().stream().map(this::humanInputFieldSummary).toList());
        return Map.copyOf(summary);
    }

    private Map<String, Object> policyConfirmationInputSummary(PolicyValidationResult validation) {
        return Map.of(
            "reason", validation.message(),
            "question", "Review and confirm whether this replanning decision may continue.",
            "blocking", true,
            "policyReasonCode", validation.reasonCode().name(),
            "inputSchema", List.of(Map.of(
                "name", "approved",
                "type", "boolean",
                "description", "Whether the policy-gated replanning decision is approved.",
                "required", true
            ))
        );
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
