package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.TaskStatus;
import java.util.List;

public record StepOutcome(
    PlanStepStatus stepStatus,
    TaskStatus taskStatus,
    String summary,
    List<String> resultRefs,
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
        summary = summary == null ? "" : summary;
        resultRefs = resultRefs == null ? List.of() : List.copyOf(resultRefs);
        blockerDetails = blockerDetails == null ? List.of() : List.copyOf(blockerDetails);
    }

    public static StepOutcome succeeded() {
        return succeeded("", List.of());
    }

    public static StepOutcome succeeded(String summary, List<String> resultRefs) {
        return new StepOutcome(PlanStepStatus.SUCCESS, null, summary, resultRefs, null, List.of(), false);
    }

    public static StepOutcome completed(String summary, List<String> resultRefs) {
        return new StepOutcome(PlanStepStatus.SUCCESS, TaskStatus.COMPLETED, summary, resultRefs, null, List.of(), false);
    }

    public static StepOutcome failed(String blockerDetail) {
        return new StepOutcome(
            PlanStepStatus.FAILED,
            TaskStatus.FAILED,
            blockerDetail == null ? "" : blockerDetail,
            List.of(),
            null,
            blockerList(blockerDetail),
            true
        );
    }

    public static StepOutcome skipped(String blockerDetail) {
        return new StepOutcome(
            PlanStepStatus.SKIPPED,
            null,
            blockerDetail == null ? "" : blockerDetail,
            List.of(),
            null,
            blockerList(blockerDetail),
            false
        );
    }

    public static StepOutcome skippedNonBlocking(String summary) {
        return new StepOutcome(
            PlanStepStatus.SKIPPED,
            null,
            summary,
            List.of(),
            null,
            List.of(),
            false
        );
    }

    public static StepOutcome blocked(String summary, List<String> blockerDetails) {
        return new StepOutcome(
            PlanStepStatus.SKIPPED,
            TaskStatus.ANALYZING_RESULTS,
            summary,
            List.of(),
            null,
            blockerDetails,
            false
        );
    }

    public static StepOutcome paused(TaskStatus taskStatus, String blockerDetail) {
        return new StepOutcome(
            PlanStepStatus.SUCCESS,
            taskStatus,
            blockerDetail == null ? "" : blockerDetail,
            List.of(),
            null,
            blockerList(blockerDetail),
            true
        );
    }

    private static List<String> blockerList(String blockerDetail) {
        return blockerDetail == null || blockerDetail.isBlank() ? List.of() : List.of(blockerDetail);
    }
}
