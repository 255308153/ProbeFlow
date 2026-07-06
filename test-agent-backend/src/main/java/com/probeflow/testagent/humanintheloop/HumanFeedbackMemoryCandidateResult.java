package com.probeflow.testagent.humanintheloop;

import com.probeflow.testagent.memory.MemoryCandidateRequest;
import java.util.List;
import java.util.Map;

public record HumanFeedbackMemoryCandidateResult(
    HumanFeedbackMemoryCandidateStatus status,
    MemoryCandidateRequest candidate,
    List<String> blockers,
    Map<String, Object> auditSummary
) {

    public HumanFeedbackMemoryCandidateResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        auditSummary = auditSummary == null ? Map.of() : Map.copyOf(auditSummary);
    }

    static HumanFeedbackMemoryCandidateResult generated(
        MemoryCandidateRequest candidate,
        Map<String, Object> auditSummary
    ) {
        return new HumanFeedbackMemoryCandidateResult(
            HumanFeedbackMemoryCandidateStatus.GENERATED,
            candidate,
            List.of(),
            auditSummary
        );
    }

    static HumanFeedbackMemoryCandidateResult rejected(
        List<String> blockers,
        Map<String, Object> auditSummary
    ) {
        return new HumanFeedbackMemoryCandidateResult(
            HumanFeedbackMemoryCandidateStatus.REJECTED,
            null,
            blockers,
            auditSummary
        );
    }
}
