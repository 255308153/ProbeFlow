package com.probeflow.testagent.controlledplanner;

import com.probeflow.testagent.orchestration.StepOutcome;
import com.probeflow.testagent.task.PlanStep;
import java.util.ArrayList;
import java.util.List;

public record LastStepOutcomeSnapshot(
    String stepStatus,
    String taskStatus,
    String summary,
    List<String> resultRefs,
    List<String> blockers,
    boolean stopOrchestration,
    String sourceStepId,
    String sourceStepType,
    String sourceStepStatus
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
        sourceStepId = clean(sourceStepId);
        sourceStepType = clean(sourceStepType);
        sourceStepStatus = clean(sourceStepStatus);
    }

    public static LastStepOutcomeSnapshot none() {
        return new LastStepOutcomeSnapshot(null, null, "", List.of(), List.of(), false, null, null, null);
    }

    public static LastStepOutcomeSnapshot from(StepOutcome outcome) {
        return from(outcome, null);
    }

    public static LastStepOutcomeSnapshot from(StepOutcome outcome, PlanStep sourceStep) {
        if (outcome == null) {
            return none();
        }
        return new LastStepOutcomeSnapshot(
            outcome.stepStatus().name(),
            outcome.taskStatus() == null ? null : outcome.taskStatus().name(),
            outcome.summary(),
            resultRefs(outcome),
            outcome.blockerDetails(),
            outcome.stopOrchestration(),
            sourceStep == null ? null : sourceStep.getStepId(),
            sourceStep == null || sourceStep.getStepType() == null ? null : sourceStep.getStepType().name(),
            sourceStep == null || sourceStep.getStepStatus() == null ? null : sourceStep.getStepStatus().name()
        );
    }

    public boolean hasBlockers() {
        return !blockers.isEmpty();
    }

    private static List<String> resultRefs(StepOutcome outcome) {
        var refs = new ArrayList<String>();
        refs.addAll(outcome.resultRefs());
        var resultRef = clean(outcome.resultRef());
        if (resultRef != null) {
            refs.add(resultRef);
        }
        return refs;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
