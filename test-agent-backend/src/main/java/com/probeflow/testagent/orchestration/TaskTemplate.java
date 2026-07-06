package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.util.List;

public record TaskTemplate(
    String templateName,
    TaskType taskType,
    PromotionMode promotionMode,
    List<TaskPlanStepTemplate> steps
) {
}
