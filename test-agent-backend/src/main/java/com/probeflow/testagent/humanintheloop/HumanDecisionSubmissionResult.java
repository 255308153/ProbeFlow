package com.probeflow.testagent.humanintheloop;

import java.util.List;
import java.util.Map;

public record HumanDecisionSubmissionResult(
    HumanDecisionSubmissionStatus status,
    HumanDecisionRecord decision,
    HumanReviewRequest request,
    List<String> blockers,
    Map<String, Object> auditSummary
) {

    public HumanDecisionSubmissionResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        auditSummary = auditSummary == null ? Map.of() : Map.copyOf(auditSummary);
    }

    static HumanDecisionSubmissionResult accepted(
        HumanDecisionRecord decision,
        HumanReviewRequest request,
        Map<String, Object> auditSummary
    ) {
        return new HumanDecisionSubmissionResult(
            HumanDecisionSubmissionStatus.ACCEPTED,
            decision,
            request,
            List.of(),
            auditSummary
        );
    }

    static HumanDecisionSubmissionResult rejected(List<String> blockers, Map<String, Object> auditSummary) {
        return new HumanDecisionSubmissionResult(
            HumanDecisionSubmissionStatus.REJECTED,
            null,
            null,
            blockers,
            auditSummary
        );
    }
}
