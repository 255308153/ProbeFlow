package com.probeflow.testagent.agentpolicy;

import java.util.List;

public record ToolOutputSchema(
    String summary,
    List<ToolSchemaField> fields
) {

    public ToolOutputSchema {
        summary = requireText(summary, "output schema summary");
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static ToolOutputSchema of(String summary, List<ToolSchemaField> fields) {
        return new ToolOutputSchema(summary, fields);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
