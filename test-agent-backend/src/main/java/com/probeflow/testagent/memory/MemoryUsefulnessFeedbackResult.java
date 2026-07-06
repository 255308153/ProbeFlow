package com.probeflow.testagent.memory;

public record MemoryUsefulnessFeedbackResult(
    MemoryUsefulnessFeedbackStatus status,
    String rejectionReason,
    MemoryUsefulnessFeedback feedback,
    LongTermMemory memory
) {
}
