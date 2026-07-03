package com.probeflow.testagent.memory;

import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TaskStateSnapshot(
    String taskId,
    TaskType taskType,
    String taskName,
    TaskStatus status,
    TaskSourceType sourceType,
    String sourceRef,
    List<String> targetApiSpecIds,
    PromotionMode promotionMode,
    MemoryRefinementStatus memoryRefinementStatus,
    TaskPriority priority,
    String creator,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt
) {
}
