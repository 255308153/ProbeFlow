package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.task.TaskStatus;
import java.util.List;

public record TaskOrchestrationResult(
    String taskId,
    TaskStatus finalStatus,
    int completedStepCount,
    List<String> blockerDetails
) {

    public TaskOrchestrationResult {
        blockerDetails = blockerDetails == null ? List.of() : List.copyOf(blockerDetails);
    }
}
