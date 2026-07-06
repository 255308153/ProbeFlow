package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.task.PlanStepType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import java.util.List;

public record TaskInitializationResult(
    String taskId,
    TaskType taskType,
    TaskStatus taskStatus,
    String templateName,
    List<PlanStepType> planStepTypes,
    boolean created
) {
}
