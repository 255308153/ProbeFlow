package com.probeflow.testagent.llm;

import java.util.Map;

public record LlmCallRequest(
    String taskId,
    String planStepId,
    String purpose,
    String templateId,
    Map<String, Object> variables,
    String provider,
    String model,
    Map<String, Object> metadata
) {

    public LlmCallRequest {
        taskId = clean(taskId);
        planStepId = clean(planStepId);
        purpose = clean(purpose);
        templateId = clean(templateId);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        provider = clean(provider);
        model = clean(model);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static LlmCallRequest forTemplate(
        String taskId,
        String planStepId,
        String templateId,
        Map<String, Object> variables,
        String provider,
        String model
    ) {
        return new LlmCallRequest(taskId, planStepId, null, templateId, variables, provider, model, Map.of());
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
