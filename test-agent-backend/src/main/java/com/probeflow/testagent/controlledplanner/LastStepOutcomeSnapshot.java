package com.probeflow.testagent.controlledplanner;

import com.probeflow.testagent.orchestration.StepOutcome;
import java.util.List;

public record LastStepOutcomeSnapshot(
    String stepStatus,
    String taskStatus,
    String summary,
    List<String> resultRefs,
    List<String> blockers,
    boolean stopOrchestration
) {

    public LastStepOutcomeSnapshot {
        stepStatus = clean(stepStatus);
        taskStatus = clean(taskStatus);
        summary = summary == null ? "" : summary.trim();
        resultRefs = resultRefs == null ? List.of() : resultRefs.stream()
            .map(LastStepOutcomeSnapshot::clean)
            .filter(value -> value != null)
            .toList();
        blockers = blockers == null ? List.of() : blockers.stream()
            .map(LastStepOutcomeSnapshot::clean)
            .filter(value -> value != null)
            .toList();
    }

    public static LastStepOutcomeSnapshot none() {
        return new LastStepOutcomeSnapshot(null, null, "", List.of(), List.of(), false);
    }

    public static LastStepOutcomeSnapshot from(StepOutcome outcome) {
        if (outcome == null) {
            return none();
        }
        return new LastStepOutcomeSnapshot(
            outcome.stepStatus().name(),
            outcome.taskStatus() == null ? null : outcome.taskStatus().name(),
            outcome.summary(),
            outcome.resultRefs(),
            outcome.blockerDetails(),
            outcome.stopOrchestration()
        );
    }

    public boolean hasBlockers() {
        return !blockers.isEmpty();
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
