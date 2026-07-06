package com.probeflow.testagent.agentpolicy;

public record ToolPolicyDecision(
    ToolPolicyStatus status,
    ToolPolicyReasonCode reasonCode,
    String message,
    ToolName toolName
) {

    public ToolPolicyDecision {
        if (status == null) {
            throw new IllegalArgumentException("tool policy status is required");
        }
        if (reasonCode == null) {
            throw new IllegalArgumentException("tool policy reason code is required");
        }
        message = message == null ? "" : message.trim();
    }

    public static ToolPolicyDecision allowed(ToolName toolName, String message) {
        return new ToolPolicyDecision(
            ToolPolicyStatus.ALLOWED,
            ToolPolicyReasonCode.ALLOWED_BY_POLICY,
            message,
            toolName
        );
    }

    public static ToolPolicyDecision requiresHumanConfirmation(
        ToolName toolName,
        ToolPolicyReasonCode reasonCode,
        String message
    ) {
        return new ToolPolicyDecision(
            ToolPolicyStatus.REQUIRES_HUMAN_CONFIRMATION,
            reasonCode,
            message,
            toolName
        );
    }

    public static ToolPolicyDecision blocked(ToolName toolName, ToolPolicyReasonCode reasonCode, String message) {
        return new ToolPolicyDecision(ToolPolicyStatus.BLOCKED, reasonCode, message, toolName);
    }

    public boolean allowed() {
        return status == ToolPolicyStatus.ALLOWED;
    }

    public boolean blocked() {
        return status == ToolPolicyStatus.BLOCKED;
    }

    public boolean requiresHumanConfirmation() {
        return status == ToolPolicyStatus.REQUIRES_HUMAN_CONFIRMATION;
    }
}
