package com.probeflow.testagent.controlledplanner;

public record ProposedPlanStep(
    String stepType,
    String title,
    String description,
    String proposedToolName
) {

    public ProposedPlanStep {
        stepType = requireText(stepType, "proposed step type");
        title = requireText(title, "proposed step title");
        description = clean(description);
        proposedToolName = clean(proposedToolName);
    }

    public static ProposedPlanStep of(String stepType, String title, String description, String proposedToolName) {
        return new ProposedPlanStep(stepType, title, description, proposedToolName);
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
}
