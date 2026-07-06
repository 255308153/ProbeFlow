package com.probeflow.testagent.humanintheloop;

import java.util.List;
import java.util.Map;

public record HumanDraftReviewDecisionResult(
    HumanDraftReviewDecisionStatus status,
    HumanReviewRequest request,
    HumanDecisionRecord decision,
    List<String> promotedCaseIds,
    List<String> discardedDraftIds,
    List<String> blockers,
    Map<String, Object> auditSummary
) {

    public HumanDraftReviewDecisionResult {
        promotedCaseIds = promotedCaseIds == null ? List.of() : List.copyOf(promotedCaseIds);
        discardedDraftIds = discardedDraftIds == null ? List.of() : List.copyOf(discardedDraftIds);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        auditSummary = auditSummary == null ? Map.of() : Map.copyOf(auditSummary);
    }

    static HumanDraftReviewDecisionResult applied(
        HumanReviewRequest request,
        HumanDecisionRecord decision,
        List<String> promotedCaseIds,
        List<String> discardedDraftIds,
        Map<String, Object> auditSummary
    ) {
        return new HumanDraftReviewDecisionResult(
            HumanDraftReviewDecisionStatus.APPLIED,
            request,
            decision,
            promotedCaseIds,
            discardedDraftIds,
            List.of(),
            auditSummary
        );
    }

    static HumanDraftReviewDecisionResult rejected(List<String> blockers, Map<String, Object> auditSummary) {
        return new HumanDraftReviewDecisionResult(
            HumanDraftReviewDecisionStatus.REJECTED,
            null,
            null,
            List.of(),
            List.of(),
            blockers,
            auditSummary
        );
    }
}
