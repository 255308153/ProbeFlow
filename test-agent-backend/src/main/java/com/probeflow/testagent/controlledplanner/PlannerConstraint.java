package com.probeflow.testagent.controlledplanner;

public record PlannerConstraint(
    String code,
    String description
) {

    public PlannerConstraint {
        code = requireText(code, "planner constraint code");
        description = requireText(description, "planner constraint description");
    }

    public static PlannerConstraint of(String code, String description) {
        return new PlannerConstraint(code, description);
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
