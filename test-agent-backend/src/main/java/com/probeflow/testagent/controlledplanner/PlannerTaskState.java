package com.probeflow.testagent.controlledplanner;

import java.util.List;

public record PlannerTaskState(
    String taskId,
    String taskType,
    String taskStatus,
    String currentPlanStepId,
    List<String> remainingStepTypes
) {

    public PlannerTaskState {
        taskId = requireText(taskId, "task id");
        taskType = clean(taskType);
        taskStatus = requireText(taskStatus, "task status");
        currentPlanStepId = clean(currentPlanStepId);
        remainingStepTypes = remainingStepTypes == null ? List.of() : remainingStepTypes.stream()
            .map(PlannerTaskState::clean)
            .filter(value -> value != null)
            .toList();
    }

    public static PlannerTaskState of(
        String taskId,
        String taskType,
        String taskStatus,
        String currentPlanStepId,
        List<String> remainingStepTypes
    ) {
        return new PlannerTaskState(taskId, taskType, taskStatus, currentPlanStepId, remainingStepTypes);
    }

    private static String requireText(String value, String label) {
        var cleaned = clean(value);
        if (cleaned == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return cleaned;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
