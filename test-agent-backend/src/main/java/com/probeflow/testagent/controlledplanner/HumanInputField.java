package com.probeflow.testagent.controlledplanner;

public record HumanInputField(
    String name,
    String type,
    String description,
    boolean required
) {

    public HumanInputField {
        name = requireText(name, "human input field name");
        type = requireText(type, "human input field type");
        description = clean(description);
    }

    public static HumanInputField required(String name, String type, String description) {
        return new HumanInputField(name, type, description, true);
    }

    public static HumanInputField optional(String name, String type, String description) {
        return new HumanInputField(name, type, description, false);
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
