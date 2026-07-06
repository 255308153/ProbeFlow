package com.probeflow.testagent.llm;

import java.util.List;

public record PromptRenderResult(
    boolean success,
    PromptTemplate template,
    String renderedPrompt,
    LlmErrorType errorType,
    String errorMessage,
    List<String> missingVariables
) {

    public PromptRenderResult {
        errorType = errorType == null ? LlmErrorType.NONE : errorType;
        errorMessage = clean(errorMessage);
        renderedPrompt = renderedPrompt == null ? "" : renderedPrompt;
        missingVariables = missingVariables == null ? List.of() : List.copyOf(missingVariables);
        if (success && template == null) {
            throw new IllegalArgumentException("successful prompt render requires a template");
        }
        if (success && errorType != LlmErrorType.NONE) {
            throw new IllegalArgumentException("successful prompt render cannot include an error type");
        }
    }

    public static PromptRenderResult success(PromptTemplate template, String renderedPrompt) {
        return new PromptRenderResult(true, template, renderedPrompt, LlmErrorType.NONE, null, List.of());
    }

    public static PromptRenderResult missingVariables(PromptTemplate template, List<String> missingVariables) {
        return new PromptRenderResult(
            false,
            template,
            "",
            LlmErrorType.TEMPLATE_RENDER_ERROR,
            "Missing prompt variables: " + String.join(", ", missingVariables),
            missingVariables
        );
    }

    public static PromptRenderResult templateNotFound(String templateRef) {
        return new PromptRenderResult(
            false,
            null,
            "",
            LlmErrorType.TEMPLATE_RENDER_ERROR,
            "Prompt template not found: " + clean(templateRef),
            List.of()
        );
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
