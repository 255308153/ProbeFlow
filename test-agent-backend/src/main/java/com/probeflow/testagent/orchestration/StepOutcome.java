package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.TaskStatus;
import java.util.List;

public record StepOutcome(
    PlanStepStatus stepStatus,
    TaskStatus taskStatus,
    String resultRef,
    List<String> blockerDetails,
    boolean stopOrchestration
) {

    public StepOutcome {
        if (stepStatus == null) {
            throw new IllegalArgumentException("Step outcome status is required");
        }
        if (stepStatus == PlanStepStatus.PENDING || stepStatus == PlanStepStatus.RUNNING) {
            throw new IllegalArgumentException("Step outcome must be terminal: " + stepStatus);
        }
        blockerDetails = blockerDetails == null ? List.of() : List.copyOf(blockerDetails);
    }

    public static StepOutcome succeeded() {
        return new StepOutcome(PlanStepStatus.SUCCESS, null, null, List.of(), false);
    }

    public static StepOutcome failed(String blockerDetail) {
        return new StepOutcome(PlanStepStatus.FAILED, TaskStatus.FAILED, null, blockerList(blockerDetail), true);
    }

    public static StepOutcome skipped(String blockerDetail) {
        return new StepOutcome(PlanStepStatus.SKIPPED, null, null, blockerList(blockerDetail), false);
    }

    public static StepOutcome paused(TaskStatus taskStatus, String blockerDetail) {
        return new StepOutcome(PlanStepStatus.SUCCESS, taskStatus, null, blockerList(blockerDetail), true);
    }

    private static List<String> blockerList(String blockerDetail) {
        return blockerDetail == null || blockerDetail.isBlank() ? List.of() : List.of(blockerDetail);
    }
}
