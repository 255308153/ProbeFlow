package com.probeflow.testagent.controlledplanner;

import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PlanDecision(
    String decisionId,
    PlanDecisionStatus status,
    PlannerAction action,
    String reasoning,
    double confidence,
    ToolRiskLevel riskLevel,
    String proposedToolName,
    ProposedPlanStep proposedPlanStep,
    RequiredHumanInput requiredHumanInput,
    List<String> blockers,
    String sourceLlmCallId,
    boolean fakeProvider
) {

    public PlanDecision {
        status = status == null ? PlanDecisionStatus.PROPOSED : status;
        if (action == null) {
            throw new IllegalArgumentException("planner action is required");
        }
        reasoning = requireText(reasoning, "planner reasoning");
        confidence = normalizeConfidence(confidence);
        riskLevel = riskLevel == null ? ToolRiskLevel.LOW : riskLevel;
        proposedToolName = clean(proposedToolName);
        blockers = blockers == null ? List.of() : blockers.stream()
            .map(PlanDecision::clean)
            .filter(value -> value != null)
            .toList();
        sourceLlmCallId = clean(sourceLlmCallId);
        decisionId = clean(decisionId);
        if (decisionId == null) {
            decisionId = stableDecisionId(status, action, reasoning, proposedToolName, blockers, sourceLlmCallId, fakeProvider);
        }
    }

    public static PlanDecision continuePlan(String reasoning, double confidence) {
        return proposed(PlannerAction.CONTINUE, reasoning, confidence, ToolRiskLevel.LOW, null, null, null, List.of());
    }

    public static PlanDecision insertStep(
        String reasoning,
        double confidence,
        ToolRiskLevel riskLevel,
        String proposedToolName,
        ProposedPlanStep proposedPlanStep
    ) {
        return proposed(PlannerAction.INSERT_STEP, reasoning, confidence, riskLevel, proposedToolName, proposedPlanStep, null, List.of());
    }

    public static PlanDecision replan(String reasoning, double confidence, ToolRiskLevel riskLevel, List<String> blockers) {
        return proposed(PlannerAction.REPLAN, reasoning, confidence, riskLevel, null, null, null, blockers);
    }

    public static PlanDecision waitForHuman(
        String reasoning,
        double confidence,
        ToolRiskLevel riskLevel,
        RequiredHumanInput requiredHumanInput
    ) {
        return proposed(PlannerAction.WAIT_FOR_HUMAN, reasoning, confidence, riskLevel, null, null, requiredHumanInput, List.of());
    }

    public static PlanDecision stop(String reasoning, double confidence, ToolRiskLevel riskLevel, List<String> blockers) {
        return proposed(PlannerAction.STOP, reasoning, confidence, riskLevel, null, null, null, blockers);
    }

    public static PlanDecision blocked(String reasoning, List<String> blockers) {
        return new PlanDecision(
            null,
            PlanDecisionStatus.BLOCKED,
            PlannerAction.STOP,
            reasoning,
            0.0d,
            ToolRiskLevel.HIGH,
            null,
            null,
            null,
            blockers,
            null,
            false
        );
    }

    public static PlanDecision failed(String reasoning, List<String> blockers) {
        return new PlanDecision(
            null,
            PlanDecisionStatus.FAILED,
            PlannerAction.STOP,
            reasoning,
            0.0d,
            ToolRiskLevel.HIGH,
            null,
            null,
            null,
            blockers,
            null,
            false
        );
    }

    public PlanDecision withSourceLlmCall(String sourceLlmCallId, boolean fakeProvider) {
        return new PlanDecision(
            decisionId,
            status,
            action,
            reasoning,
            confidence,
            riskLevel,
            proposedToolName,
            proposedPlanStep,
            requiredHumanInput,
            blockers,
            sourceLlmCallId,
            fakeProvider
        );
    }

    public boolean proposed() {
        return status == PlanDecisionStatus.PROPOSED;
    }

    public boolean blocked() {
        return status == PlanDecisionStatus.BLOCKED;
    }

    public boolean failed() {
        return status == PlanDecisionStatus.FAILED;
    }

    public Map<String, Object> auditSummary() {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("decisionId", decisionId);
        summary.put("status", status.name());
        summary.put("action", action.name());
        summary.put("confidence", confidence);
        summary.put("riskLevel", riskLevel.name());
        summary.put("reasoning", reasoning);
        summary.put("proposedToolName", proposedToolName);
        summary.put("proposedPlanStepType", proposedPlanStep == null ? null : proposedPlanStep.stepType());
        summary.put("requiresHumanInput", requiredHumanInput != null);
        summary.put("blockers", blockers);
        summary.put("sourceLlmCallId", sourceLlmCallId);
        summary.put("fakeProvider", fakeProvider);
        return Collections.unmodifiableMap(summary);
    }

    private static PlanDecision proposed(
        PlannerAction action,
        String reasoning,
        double confidence,
        ToolRiskLevel riskLevel,
        String proposedToolName,
        ProposedPlanStep proposedPlanStep,
        RequiredHumanInput requiredHumanInput,
        List<String> blockers
    ) {
        return new PlanDecision(
            null,
            PlanDecisionStatus.PROPOSED,
            action,
            reasoning,
            confidence,
            riskLevel,
            proposedToolName,
            proposedPlanStep,
            requiredHumanInput,
            blockers,
            null,
            false
        );
    }

    private static double normalizeConfidence(double confidence) {
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            return 0.0d;
        }
        if (confidence < 0.0d) {
            return 0.0d;
        }
        if (confidence > 1.0d) {
            return 1.0d;
        }
        return confidence;
    }

    private static String requireText(String value, String label) {
        var cleaned = clean(value);
        if (cleaned == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return cleaned;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String stableDecisionId(
        PlanDecisionStatus status,
        PlannerAction action,
        String reasoning,
        String proposedToolName,
        List<String> blockers,
        String sourceLlmCallId,
        boolean fakeProvider
    ) {
        var seed = status + "|" + action + "|" + reasoning + "|" + proposedToolName + "|" + blockers + "|" + sourceLlmCallId + "|" + fakeProvider;
        return "plan-" + sha256(seed).substring(0, 16);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
