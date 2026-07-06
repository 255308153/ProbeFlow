package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.task.PlanStepType;

public record TaskPlanStepTemplate(
    int order,
    PlanStepType stepType,
    String goal
) {
}
