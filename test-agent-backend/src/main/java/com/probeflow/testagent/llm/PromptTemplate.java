package com.probeflow.testagent.llm;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public record PromptTemplate(
    String templateId,
    String purpose,
    String version,
    String body,
    List<String> requiredVariables,
    String outputConstraint
) {

    public PromptTemplate {
        templateId = required("templateId", templateId);
        purpose = required("purpose", purpose);
        version = required("version", version);
        body = body == null ? "" : body;
        requiredVariables = requiredVariables == null
            ? List.of()
            : requiredVariables.stream()
                .map(variable -> required("requiredVariable", variable))
                .distinct()
                .sorted()
                .toList();
        outputConstraint = clean(outputConstraint);
    }

    public PromptRenderResult render(Map<String, ?> variables) {
        var provided = variables == null ? Map.<String, Object>of() : variables;
        var missing = requiredVariables.stream()
            .filter(variable -> !provided.containsKey(variable) || provided.get(variable) == null)
            .sorted(Comparator.naturalOrder())
            .toList();
        if (!missing.isEmpty()) {
            return PromptRenderResult.missingVariables(this, missing);
        }

        var rendered = body;
        for (var variable : requiredVariables) {
            rendered = rendered.replace("{{" + variable + "}}", String.valueOf(provided.get(variable)));
        }
        if (outputConstraint != null) {
            rendered = rendered + "\n\nOutput constraint: " + outputConstraint;
        }
        return PromptRenderResult.success(this, rendered);
    }

    private static String required(String field, String value) {
        var cleaned = clean(value);
        if (cleaned == null) {
            throw new IllegalArgumentException(field + " is required");
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
