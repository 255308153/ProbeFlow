package com.probeflow.testagent.replanning;

import com.probeflow.testagent.orchestration.StepOutcome;
import java.util.Map;
import org.springframework.util.StringUtils;

public record ReplanningRequest(
    String taskId,
    ReplanningTrigger trigger,
    String sourceStepId,
    StepOutcome stepOutcome,
    Map<String, Object> humanInput,
    boolean reviewCompleted
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
    }

    public static ReplanningRequest of(String taskId, ReplanningTrigger trigger) {
        return new ReplanningRequest(taskId, trigger, null, null, Map.of(), false);
    }
}
