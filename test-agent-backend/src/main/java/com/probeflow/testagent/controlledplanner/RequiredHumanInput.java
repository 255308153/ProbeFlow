package com.probeflow.testagent.controlledplanner;

import java.util.List;

public record RequiredHumanInput(
    String reason,
    String question,
    List<HumanInputField> inputSchema,
    boolean blocking
) {

    public RequiredHumanInput {
        reason = requireText(reason, "human input reason");
        question = requireText(question, "human input question");
        inputSchema = inputSchema == null ? List.of() : List.copyOf(inputSchema);
    }

    public static RequiredHumanInput blocking(String reason, String question, List<HumanInputField> inputSchema) {
        return new RequiredHumanInput(reason, question, inputSchema, true);
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
