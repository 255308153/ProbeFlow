package com.probeflow.testagent.agentpolicy;

import java.util.List;

public record PlannerToolSchemaFieldView(
    String name,
    String type,
    boolean required,
    String description,
    List<String> allowedValues
) {

    public PlannerToolSchemaFieldView {
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    public static PlannerToolSchemaFieldView from(ToolSchemaField field) {
        return new PlannerToolSchemaFieldView(
            field.name(),
            field.type().name(),
            field.required(),
            field.description(),
            field.allowedValues()
        );
    }
}
