package com.probeflow.testagent.replanning;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReplanningResult(
    ReplanningStatus status,
    ReplanningTrigger trigger,
    List<String> blockers,
    Map<String, Object> decisionSummary,
    Map<String, Object> policySummary,
    Map<String, Object> planMutationSummary,
    List<String> insertedStepIds,
    List<String> skippedStepIds
) {

    public ReplanningResult {
        if (status == null) {
            throw new IllegalArgumentException("Replanning status is required");
        }
        blockers = blockers == null ? List.of() : blockers.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .toList();
        decisionSummary = decisionSummary == null ? Map.of() : Map.copyOf(decisionSummary);
        policySummary = policySummary == null ? Map.of() : Map.copyOf(policySummary);
        planMutationSummary = planMutationSummary == null ? Map.of() : Map.copyOf(planMutationSummary);
        insertedStepIds = cleanIds(insertedStepIds);
        skippedStepIds = cleanIds(skippedStepIds);
    }

    public static ReplanningResult noop(ReplanningTrigger trigger, String reason) {
        return new ReplanningResult(
            ReplanningStatus.NOOP,
            trigger,
            List.of(),
            summary("plannerCalled", false, "reason", reason),
            summary("policyValidated", false, "reason", "Policy validation is not part of the minimal replanning entrypoint."),
            summary("mutationApplied", false, "reason", "No plan mutation was requested."),
            List.of(),
            List.of()
        );
    }

    public static ReplanningResult notTriggerable(ReplanningTrigger trigger, List<String> blockers) {
        return new ReplanningResult(
            ReplanningStatus.NOT_TRIGGERABLE,
            trigger,
            blockers,
            summary("plannerCalled", false, "reason", "Task state is not eligible for replanning."),
            summary("policyValidated", false, "reason", "Policy validation was not reached."),
            summary("mutationApplied", false, "reason", "Plan remained unchanged."),
            List.of(),
            List.of()
        );
    }

    public static ReplanningResult failed(ReplanningTrigger trigger, List<String> blockers) {
        return new ReplanningResult(
            ReplanningStatus.FAILED,
            trigger,
            blockers,
            summary("plannerCalled", false, "reason", "Replanning failed before planner execution."),
            summary("policyValidated", false, "reason", "Policy validation was not reached."),
            summary("mutationApplied", false, "reason", "Plan remained unchanged."),
            List.of(),
            List.of()
        );
    }

    private static Map<String, Object> summary(String firstKey, Object firstValue, String secondKey, Object secondValue) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put(firstKey, firstValue);
        summary.put(secondKey, secondValue);
        return Map.copyOf(summary);
    }

    private static List<String> cleanIds(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .toList();
    }
}
