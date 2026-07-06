package com.probeflow.testagent.humanintheloop;

public record HumanDraftReviewRequest(
    String taskId,
    String sourceStepId,
    String waitingReason
) {

    public HumanDraftReviewRequest {
        taskId = trimToEmpty(taskId);
        sourceStepId = trimToNull(sourceStepId);
        waitingReason = trimToNull(waitingReason);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
