package com.probeflow.testagent.policyvalidator;

import com.probeflow.testagent.controlledplanner.PlanDecision;
import com.probeflow.testagent.controlledplanner.PlannerAction;
import com.probeflow.testagent.controlledplanner.RequiredHumanInput;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PolicyValidationResult(
    PolicyValidationStatus status,
    PolicyValidationReasonCode reasonCode,
    String message,
    List<String> blockers,
    String decisionId,
    PlannerAction plannerAction,
    String proposedToolName,
    String sourceLlmCallId,
    RequiredHumanInput requiredHumanInput
) {

    public PolicyValidationResult {
        if (status == null) {
            throw new IllegalArgumentException("policy validation status is required");
        }
        if (reasonCode == null) {
            throw new IllegalArgumentException("policy validation reason code is required");
        }
        message = message == null ? "" : message.trim();
        blockers = blockers == null ? List.of() : blockers.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .toList();
    }

    public static PolicyValidationResult allowed(
        PlanDecision decision,
        PolicyValidationReasonCode reasonCode,
        String message
    ) {
        return fromDecision(PolicyValidationStatus.ALLOWED, reasonCode, message, List.of(), decision);
    }

    public static PolicyValidationResult blocked(
        PlanDecision decision,
        PolicyValidationReasonCode reasonCode,
        String message,
        List<String> blockers
    ) {
        return fromDecision(PolicyValidationStatus.BLOCKED, reasonCode, message, blockers, decision);
    }

    public static PolicyValidationResult requiresHumanConfirmation(
        PlanDecision decision,
        PolicyValidationReasonCode reasonCode,
        String message,
        List<String> blockers
    ) {
        return fromDecision(PolicyValidationStatus.REQUIRES_HUMAN_CONFIRMATION, reasonCode, message, blockers, decision);
    }

    public boolean allowed() {
        return status == PolicyValidationStatus.ALLOWED;
    }

    public boolean requiresHumanConfirmation() {
        return status == PolicyValidationStatus.REQUIRES_HUMAN_CONFIRMATION;
    }

    public boolean blocked() {
        return status == PolicyValidationStatus.BLOCKED;
    }

    public Map<String, Object> auditSummary() {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("decisionId", decisionId);
        summary.put("sourceLlmCallId", sourceLlmCallId);
        summary.put("plannerAction", plannerAction == null ? null : plannerAction.name());
        summary.put("proposedToolName", proposedToolName);
        summary.put("requiresHumanInput", requiredHumanInput != null);
        summary.put("validationStatus", status.name());
        summary.put("reasonCode", reasonCode.name());
        summary.put("message", message);
        summary.put("blockers", blockers);
        return Collections.unmodifiableMap(summary);
    }

    private static PolicyValidationResult fromDecision(
        PolicyValidationStatus status,
        PolicyValidationReasonCode reasonCode,
        String message,
        List<String> blockers,
        PlanDecision decision
    ) {
        return new PolicyValidationResult(
            status,
            reasonCode,
            message,
            blockers,
            decision == null ? null : decision.decisionId(),
            decision == null ? null : decision.action(),
            decision == null ? null : decision.proposedToolName(),
            decision == null ? null : decision.sourceLlmCallId(),
            decision == null ? null : decision.requiredHumanInput()
        );
    }
}
