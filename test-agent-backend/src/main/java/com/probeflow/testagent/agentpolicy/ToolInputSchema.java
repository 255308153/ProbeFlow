package com.probeflow.testagent.agentpolicy;

import java.util.List;

public record ToolInputSchema(
    String description,
    List<ToolSchemaField> fields,
    String example
) {

    public ToolInputSchema {
        description = requireText(description, "input schema description");
        fields = fields == null ? List.of() : List.copyOf(fields);
        example = clean(example);
    }

    public static ToolInputSchema of(String description, List<ToolSchemaField> fields, String example) {
        return new ToolInputSchema(description, fields, example);
    }

    public List<ToolSchemaField> requiredFields() {
        return fields.stream()
            .filter(ToolSchemaField::required)
            .toList();
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
