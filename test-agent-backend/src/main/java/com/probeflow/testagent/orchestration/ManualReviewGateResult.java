package com.probeflow.testagent.orchestration;

import java.util.List;

public record ManualReviewGateResult(
    boolean ready,
    List<String> promotedCaseIds,
    List<String> pendingDraftIds,
    List<String> discardedDraftIds,
    List<String> blockerDetails
) {

    public ManualReviewGateResult {
        promotedCaseIds = promotedCaseIds == null ? List.of() : List.copyOf(promotedCaseIds);
        pendingDraftIds = pendingDraftIds == null ? List.of() : List.copyOf(pendingDraftIds);
        discardedDraftIds = discardedDraftIds == null ? List.of() : List.copyOf(discardedDraftIds);
        blockerDetails = blockerDetails == null ? List.of() : List.copyOf(blockerDetails);
    }

    static ManualReviewGateResult notRequired() {
        return new ManualReviewGateResult(true, List.of(), List.of(), List.of(), List.of());
    }
}
