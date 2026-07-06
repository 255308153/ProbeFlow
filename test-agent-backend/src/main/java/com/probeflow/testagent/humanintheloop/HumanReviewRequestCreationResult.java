package com.probeflow.testagent.humanintheloop;

import java.util.List;

public record HumanReviewRequestCreationResult(
    HumanReviewRequestCreationStatus status,
    HumanReviewRequest request,
    List<String> blockers
) {

    public HumanReviewRequestCreationResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }

    static HumanReviewRequestCreationResult created(HumanReviewRequest request) {
        return new HumanReviewRequestCreationResult(HumanReviewRequestCreationStatus.CREATED, request, List.of());
    }

    static HumanReviewRequestCreationResult existingPending(HumanReviewRequest request) {
        return new HumanReviewRequestCreationResult(HumanReviewRequestCreationStatus.EXISTING_PENDING, request, List.of());
    }

    static HumanReviewRequestCreationResult rejected(List<String> blockers) {
        return new HumanReviewRequestCreationResult(HumanReviewRequestCreationStatus.REJECTED, null, blockers);
    }
}
