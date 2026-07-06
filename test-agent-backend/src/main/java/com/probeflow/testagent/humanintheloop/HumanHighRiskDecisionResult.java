package com.probeflow.testagent.humanintheloop;

import java.util.List;
import java.util.Map;

public record HumanHighRiskDecisionResult(
    HumanHighRiskDecisionStatus status,
    HumanReviewRequest request,
    HumanDecisionRecord decision,
    List<String> blockers,
    Map<String, Object> auditSummary
) {

    public HumanHighRiskDecisionResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        auditSummary = auditSummary == null ? Map.of() : Map.copyOf(auditSummary);
    }

    static HumanHighRiskDecisionResult approved(
        HumanReviewRequest request,
        HumanDecisionRecord decision,
        Map<String, Object> auditSummary
    ) {
        return new HumanHighRiskDecisionResult(
            HumanHighRiskDecisionStatus.APPROVED,
            request,
            decision,
            List.of(),
            auditSummary
        );
    }

    static HumanHighRiskDecisionResult denied(
        HumanReviewRequest request,
        HumanDecisionRecord decision,
        Map<String, Object> auditSummary
    ) {
        return new HumanHighRiskDecisionResult(
            HumanHighRiskDecisionStatus.DENIED,
            request,
            decision,
            List.of(),
            auditSummary
        );
    }

    static HumanHighRiskDecisionResult rejected(List<String> blockers, Map<String, Object> auditSummary) {
        return new HumanHighRiskDecisionResult(
            HumanHighRiskDecisionStatus.REJECTED,
            null,
            null,
            blockers,
            auditSummary
        );
    }
}
