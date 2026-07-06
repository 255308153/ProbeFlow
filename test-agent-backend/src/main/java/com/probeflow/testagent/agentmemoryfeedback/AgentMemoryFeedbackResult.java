package com.probeflow.testagent.agentmemoryfeedback;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public record AgentMemoryFeedbackResult(
    MemoryCandidateProcessingStatus status,
    String candidateId,
    String memoryId,
    String rejectionReason,
    List<String> blockers,
    Map<String, Object> auditSummary
) {

    public AgentMemoryFeedbackResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        auditSummary = auditSummary == null ? Map.of() : Map.copyOf(auditSummary);
    }

    public static AgentMemoryFeedbackResult pending(MemoryCandidateRecord record) {
        return new AgentMemoryFeedbackResult(
            MemoryCandidateProcessingStatus.PENDING,
            record.getCandidateId(),
            record.getMemoryId(),
            record.getRejectionReason(),
            List.of(),
            record.getAuditSummary()
        );
    }

    public static AgentMemoryFeedbackResult duplicate(MemoryCandidateRecord record) {
        var audit = new LinkedHashMap<>(record.getAuditSummary());
        audit.put("idempotent", true);
        return new AgentMemoryFeedbackResult(
            MemoryCandidateProcessingStatus.DUPLICATE,
            record.getCandidateId(),
            record.getMemoryId(),
            record.getRejectionReason(),
            List.of(),
            audit
        );
    }

    public static AgentMemoryFeedbackResult rejected(String rejectionReason, List<String> blockers, Map<String, Object> auditSummary) {
        return new AgentMemoryFeedbackResult(
            MemoryCandidateProcessingStatus.REJECTED,
            null,
            null,
            rejectionReason,
            blockers,
            auditSummary
        );
    }
}
