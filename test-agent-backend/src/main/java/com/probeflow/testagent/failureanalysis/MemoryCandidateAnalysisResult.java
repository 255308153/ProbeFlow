package com.probeflow.testagent.failureanalysis;

public record MemoryCandidateAnalysisResult(
    boolean attempted,
    boolean accepted,
    boolean created,
    boolean merged,
    String rejectionReason,
    String memoryId
) {

    public static MemoryCandidateAnalysisResult notAttempted() {
        return new MemoryCandidateAnalysisResult(false, false, false, false, null, null);
    }
}
