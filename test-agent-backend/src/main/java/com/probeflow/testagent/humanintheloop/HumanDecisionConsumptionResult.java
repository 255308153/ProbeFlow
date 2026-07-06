package com.probeflow.testagent.humanintheloop;

import java.util.List;
import java.util.Map;

public record HumanDecisionConsumptionResult(
    HumanDecisionConsumptionStatus status,
    HumanReviewRequest request,
    List<String> blockers,
    Map<String, Object> auditSummary
) {

    public HumanDecisionConsumptionResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        auditSummary = auditSummary == null ? Map.of() : Map.copyOf(auditSummary);
    }

    static HumanDecisionConsumptionResult consumed(HumanReviewRequest request, Map<String, Object> auditSummary) {
        return new HumanDecisionConsumptionResult(HumanDecisionConsumptionStatus.CONSUMED, request, List.of(), auditSummary);
    }

    static HumanDecisionConsumptionResult alreadyConsumed(HumanReviewRequest request, Map<String, Object> auditSummary) {
        return new HumanDecisionConsumptionResult(
            HumanDecisionConsumptionStatus.ALREADY_CONSUMED,
            request,
            List.of(),
            auditSummary
        );
    }

    static HumanDecisionConsumptionResult rejected(List<String> blockers, Map<String, Object> auditSummary) {
        return new HumanDecisionConsumptionResult(HumanDecisionConsumptionStatus.REJECTED, null, blockers, auditSummary);
    }
}
