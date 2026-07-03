package com.probeflow.testagent.memory;

public record MemoryRefineryResult(
    boolean accepted,
    boolean created,
    boolean duplicateSuppressed,
    String rejectionReason,
    RefinedMemoryView memory
) {
}
