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
    Map<String, Object> metadata,
    LlmExecutionOptions executionOptions
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
        executionOptions = executionOptions == null
            ? LlmExecutionOptions.of(provider, model)
            : executionOptions.withFallbacks(provider, model);
        provider = executionOptions.provider();
        model = executionOptions.model();
    }

    public LlmCallRequest(
        String taskId,
        String planStepId,
        String purpose,
        String templateId,
        Map<String, Object> variables,
        String provider,
        String model,
        Map<String, Object> metadata
    ) {
        this(taskId, planStepId, purpose, templateId, variables, provider, model, metadata, null);
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

    public static LlmCallRequest forTemplate(
        String taskId,
        String planStepId,
        String templateId,
        Map<String, Object> variables,
        LlmExecutionOptions executionOptions
    ) {
        return new LlmCallRequest(taskId, planStepId, null, templateId, variables, null, null, Map.of(), executionOptions);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
