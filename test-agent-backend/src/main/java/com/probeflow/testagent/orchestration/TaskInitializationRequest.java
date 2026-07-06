package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.util.List;
import java.util.Map;

public record TaskInitializationRequest(
    String existingTaskId,
    TaskType taskType,
    String taskName,
    TaskSourceType sourceType,
    String sourceRef,
    PromotionMode promotionMode,
    List<String> targetApiSpecIds,
    List<String> selectedCaseIds,
    TaskPriority priority,
    String creator,
    Map<String, Object> metadata
) {
}
