package com.probeflow.testagent.humanintheloop;

import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

public record HumanReviewRequestCreateRequest(
    String taskId,
    String sourceStepId,
    HumanRequestType requestType,
    String waitingReason,
    List<Map<String, Object>> requiredInputSchema,
    ToolRiskLevel riskLevel,
    String sourceTrigger,
    String plannerDecisionId,
    String policyReason,
    Map<String, Object> metadata,
    Instant expiresAt
) {

    public HumanReviewRequestCreateRequest {
        taskId = trimRequired(taskId, "taskId");
        sourceStepId = trimToNull(sourceStepId);
        if (requestType == null) {
            throw new IllegalArgumentException("requestType is required");
        }
        waitingReason = trimRequired(waitingReason, "waitingReason");
        requiredInputSchema = copySchema(requiredInputSchema);
        riskLevel = riskLevel == null ? ToolRiskLevel.LOW : riskLevel;
        sourceTrigger = trimRequired(sourceTrigger, "sourceTrigger");
        plannerDecisionId = trimToNull(plannerDecisionId);
        policyReason = trimToNull(policyReason);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static String trimRequired(String value, String fieldName) {
        var trimmed = trimToNull(value);
        if (!StringUtils.hasText(trimmed)) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return StringUtils.hasText(trimmed) ? trimmed : null;
    }

    private static List<Map<String, Object>> copySchema(List<Map<String, Object>> schema) {
        if (schema == null || schema.isEmpty()) {
            return List.of();
        }
        return schema.stream()
            .map(field -> field == null ? Map.<String, Object>of() : Map.copyOf(new LinkedHashMap<>(field)))
            .toList();
    }
}
