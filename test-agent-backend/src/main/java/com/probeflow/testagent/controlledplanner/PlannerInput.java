package com.probeflow.testagent.controlledplanner;

import com.probeflow.testagent.agentpolicy.AgentTaskPhase;
import com.probeflow.testagent.agentpolicy.AgentWorkflowMode;
import com.probeflow.testagent.agentpolicy.PlannerSafeToolView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public record PlannerInput(
    String planningTraceId,
    PlannerTaskState taskState,
    AgentTaskPhase currentPhase,
    AgentWorkflowMode workflowMode,
    LastStepOutcomeSnapshot lastStepOutcome,
    ContextBundleSummary contextSummary,
    List<PlannerSafeToolView> availableTools,
    List<PlannerConstraint> constraints
) {

    public PlannerInput {
        if (taskState == null) {
            throw new IllegalArgumentException("planner task state is required");
        }
        currentPhase = currentPhase == null ? AgentTaskPhase.ANY : currentPhase;
        workflowMode = workflowMode == null ? AgentWorkflowMode.SEMI_AUTOMATIC : workflowMode;
        lastStepOutcome = lastStepOutcome == null ? LastStepOutcomeSnapshot.none() : lastStepOutcome;
        contextSummary = contextSummary == null ? ContextBundleSummary.empty() : contextSummary;
        availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        planningTraceId = clean(planningTraceId);
        if (planningTraceId == null) {
            planningTraceId = stableTraceId(taskState, currentPhase, workflowMode, lastStepOutcome, contextSummary, availableTools);
        }
    }

    public static PlannerInput of(
        PlannerTaskState taskState,
        AgentTaskPhase currentPhase,
        AgentWorkflowMode workflowMode,
        LastStepOutcomeSnapshot lastStepOutcome,
        ContextBundleSummary contextSummary,
        List<PlannerSafeToolView> availableTools,
        List<PlannerConstraint> constraints
    ) {
        return new PlannerInput(
            null,
            taskState,
            currentPhase,
            workflowMode,
            lastStepOutcome,
            contextSummary,
            availableTools,
            constraints
        );
    }

    private static String stableTraceId(
        PlannerTaskState taskState,
        AgentTaskPhase currentPhase,
        AgentWorkflowMode workflowMode,
        LastStepOutcomeSnapshot lastStepOutcome,
        ContextBundleSummary contextSummary,
        List<PlannerSafeToolView> tools
    ) {
        var seed = taskState.taskId()
            + "|" + currentPhase
            + "|" + workflowMode
            + "|" + lastStepOutcome.stepStatus()
            + "|" + lastStepOutcome.sourceStepId()
            + "|" + lastStepOutcome.sourceStepType()
            + "|" + lastStepOutcome.sourceStepStatus()
            + "|" + lastStepOutcome.summary()
            + "|" + contextSummary.citationRefs()
            + "|" + tools.stream().map(PlannerSafeToolView::name).sorted().toList();
        return "planner-" + sha256(seed).substring(0, 16);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
