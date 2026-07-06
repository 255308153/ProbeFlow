package com.probeflow.testagent.agentpolicy;

import java.util.List;
import java.util.Set;

public record ToolContract(
    ToolName name,
    ToolCapabilityGroup capabilityGroup,
    String description,
    ToolInputSchema inputSchema,
    ToolOutputSchema outputSchema,
    Set<ToolPrecondition> preconditions,
    ToolRiskLevel riskLevel,
    ToolExecutionMode executionMode,
    boolean humanConfirmationRequired,
    Set<String> tags
) {

    public ToolContract {
        if (name == null) {
            throw new IllegalArgumentException("tool name is required");
        }
        if (capabilityGroup == null) {
            throw new IllegalArgumentException("tool capability group is required");
        }
        description = requireText(description, "tool description");
        if (inputSchema == null) {
            throw new IllegalArgumentException("tool input schema is required");
        }
        if (outputSchema == null) {
            throw new IllegalArgumentException("tool output schema is required");
        }
        preconditions = preconditions == null ? Set.of() : Set.copyOf(preconditions);
        riskLevel = riskLevel == null ? ToolRiskLevel.MEDIUM : riskLevel;
        executionMode = executionMode == null ? ToolExecutionMode.HUMAN_CONFIRMATION_REQUIRED : executionMode;
        humanConfirmationRequired = humanConfirmationRequired
            || executionMode == ToolExecutionMode.HUMAN_CONFIRMATION_REQUIRED;
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    public static ToolContract of(
        ToolName name,
        ToolCapabilityGroup capabilityGroup,
        String description,
        ToolInputSchema inputSchema,
        ToolOutputSchema outputSchema,
        Set<ToolPrecondition> preconditions,
        ToolRiskLevel riskLevel,
        ToolExecutionMode executionMode,
        boolean humanConfirmationRequired,
        Set<String> tags
    ) {
        return new ToolContract(
            name,
            capabilityGroup,
            description,
            inputSchema,
            outputSchema,
            preconditions,
            riskLevel,
            executionMode,
            humanConfirmationRequired,
            tags
        );
    }

    public boolean readOnly() {
        return tags.contains("read-only");
    }

    public List<String> preconditionNames() {
        return preconditions.stream()
            .map(Enum::name)
            .sorted()
            .toList();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
