package com.probeflow.testagent.agentpolicy;

import java.util.List;

public record PlannerSafeToolView(
    String name,
    String capabilityGroup,
    String description,
    List<PlannerToolSchemaFieldView> inputSchema,
    String inputExample,
    String outputSummary,
    List<PlannerToolSchemaFieldView> outputSchema,
    String riskLevel,
    String executionMode,
    boolean humanConfirmationRequired,
    List<String> preconditions,
    List<String> tags,
    ToolPolicyStatus policyStatus,
    ToolPolicyReasonCode policyReasonCode,
    String policyMessage
) {

    public PlannerSafeToolView {
        inputSchema = inputSchema == null ? List.of() : List.copyOf(inputSchema);
        outputSchema = outputSchema == null ? List.of() : List.copyOf(outputSchema);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        tags = tags == null ? List.of() : List.copyOf(tags);
        policyMessage = policyMessage == null ? "" : policyMessage;
    }

    public boolean blocked() {
        return policyStatus == ToolPolicyStatus.BLOCKED;
    }
}
