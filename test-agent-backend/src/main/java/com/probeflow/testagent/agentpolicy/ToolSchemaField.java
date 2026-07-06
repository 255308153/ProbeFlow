package com.probeflow.testagent.agentpolicy;

import java.util.List;

public record ToolSchemaField(
    String name,
    ToolSchemaType type,
    boolean required,
    String description,
    List<String> allowedValues
) {

    public ToolSchemaField {
        name = requireText(name, "schema field name");
        type = type == null ? ToolSchemaType.STRING : type;
        description = clean(description);
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    public static ToolSchemaField required(String name, ToolSchemaType type, String description) {
        return new ToolSchemaField(name, type, true, description, List.of());
    }

    public static ToolSchemaField requiredEnum(String name, String description, List<String> allowedValues) {
        return new ToolSchemaField(name, ToolSchemaType.STRING, true, description, allowedValues);
    }

    public static ToolSchemaField optional(String name, ToolSchemaType type, String description) {
        return new ToolSchemaField(name, type, false, description, List.of());
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
