package com.probeflow.testagent.humanintheloop;

import java.util.List;
import java.util.Map;

public record HumanInputResolutionResult(
    HumanInputResolutionStatus status,
    HumanReviewRequest request,
    HumanDecisionRecord decision,
    List<String> blockers,
    Map<String, Object> auditSummary
) {

    public HumanInputResolutionResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        auditSummary = auditSummary == null ? Map.of() : Map.copyOf(auditSummary);
    }

    static HumanInputResolutionResult applied(
        HumanReviewRequest request,
        HumanDecisionRecord decision,
        Map<String, Object> auditSummary
    ) {
        return new HumanInputResolutionResult(
            HumanInputResolutionStatus.APPLIED,
            request,
            decision,
            List.of(),
            auditSummary
        );
    }

    static HumanInputResolutionResult rejected(List<String> blockers, Map<String, Object> auditSummary) {
        return new HumanInputResolutionResult(
            HumanInputResolutionStatus.REJECTED,
            null,
            null,
            blockers,
            auditSummary
        );
    }
}
