package com.probeflow.testagent.replanning;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.controlledplanner.ContextBundleSummary;
import com.probeflow.testagent.controlledplanner.PlannerConstraint;
import com.probeflow.testagent.orchestration.StepOutcome;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

public record ReplanningRequest(
    String taskId,
    ReplanningTrigger trigger,
    String sourceStepId,
    StepOutcome stepOutcome,
    Map<String, Object> humanInput,
    boolean reviewCompleted,
    AgentPolicy policy,
    ContextBundleSummary contextSummary,
    List<PlannerConstraint> constraints
) {

    public ReplanningRequest {
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalArgumentException("Task id is required");
        }
        if (trigger == null) {
            throw new IllegalArgumentException("Replanning trigger is required");
        }
        taskId = taskId.trim();
        sourceStepId = StringUtils.hasText(sourceStepId) ? sourceStepId.trim() : null;
        humanInput = humanInput == null ? Map.of() : Map.copyOf(humanInput);
        policy = policy == null ? AgentPolicy.v2Phase2Default() : policy;
        contextSummary = contextSummary == null ? ContextBundleSummary.empty() : contextSummary;
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }

    public ReplanningRequest(
        String taskId,
        ReplanningTrigger trigger,
        String sourceStepId,
        StepOutcome stepOutcome,
        Map<String, Object> humanInput,
        boolean reviewCompleted
    ) {
        this(
            taskId,
            trigger,
            sourceStepId,
            stepOutcome,
            humanInput,
            reviewCompleted,
            AgentPolicy.v2Phase2Default(),
            ContextBundleSummary.empty(),
            List.of()
        );
    }

    public static ReplanningRequest of(String taskId, ReplanningTrigger trigger) {
        return new ReplanningRequest(taskId, trigger, null, null, Map.of(), false);
    }
}
