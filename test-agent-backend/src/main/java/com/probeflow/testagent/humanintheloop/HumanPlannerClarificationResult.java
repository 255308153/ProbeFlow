package com.probeflow.testagent.humanintheloop;

import java.util.List;
import java.util.Map;

public record HumanPlannerClarificationResult(
    HumanPlannerClarificationStatus status,
    HumanReviewRequest request,
    HumanDecisionRecord decision,
    List<String> blockers,
    Map<String, Object> humanInput,
    String recoveryTrigger,
    Map<String, Object> auditSummary
) {

    public HumanPlannerClarificationResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        humanInput = humanInput == null ? Map.of() : Map.copyOf(humanInput);
        auditSummary = auditSummary == null ? Map.of() : Map.copyOf(auditSummary);
    }

    static HumanPlannerClarificationResult applied(
        HumanReviewRequest request,
        HumanDecisionRecord decision,
        Map<String, Object> humanInput,
        String recoveryTrigger,
        Map<String, Object> auditSummary
    ) {
        return new HumanPlannerClarificationResult(
            HumanPlannerClarificationStatus.APPLIED,
            request,
            decision,
            List.of(),
            humanInput,
            recoveryTrigger,
            auditSummary
        );
    }

    static HumanPlannerClarificationResult rejected(List<String> blockers, Map<String, Object> auditSummary) {
        return new HumanPlannerClarificationResult(
            HumanPlannerClarificationStatus.REJECTED,
            null,
            null,
            blockers,
            Map.of(),
            null,
            auditSummary
        );
    }
}
