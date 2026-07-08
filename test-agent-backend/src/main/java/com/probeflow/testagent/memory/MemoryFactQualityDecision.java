package com.probeflow.testagent.memory;

public record MemoryFactQualityDecision(
    MemoryFactQualityStatus status,
    String rejectionReason
) {

    public static MemoryFactQualityDecision accepted() {
        return new MemoryFactQualityDecision(MemoryFactQualityStatus.ACCEPTED, null);
    }

    public static MemoryFactQualityDecision rejected(String reason) {
        return new MemoryFactQualityDecision(MemoryFactQualityStatus.REJECTED, reason);
    }

    public boolean isAccepted() {
        return status == MemoryFactQualityStatus.ACCEPTED;
    }
}
