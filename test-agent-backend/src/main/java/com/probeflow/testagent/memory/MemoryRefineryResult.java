package com.probeflow.testagent.memory;

import java.util.Map;

public record MemoryRefineryResult(
    boolean accepted,
    boolean created,
    boolean duplicateSuppressed,
    String rejectionReason,
    RefinedMemoryView memory,
    Map<String, Object> auditSummary
) {
    public MemoryRefineryResult(
        boolean accepted,
        boolean created,
        boolean duplicateSuppressed,
        String rejectionReason,
        RefinedMemoryView memory
    ) {
        this(accepted, created, duplicateSuppressed, rejectionReason, memory, Map.of());
    }
}
