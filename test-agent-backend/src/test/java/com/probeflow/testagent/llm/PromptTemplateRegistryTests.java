package com.probeflow.testagent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PromptTemplateRegistryTests {

    @Test
    void registryResolvesTemplatesByIdOrPurposeAndExposesVersionForAudit() {
        var registry = new PromptTemplateRegistry();

        var byId = registry.findById("v2.failure-insight.v1").orElseThrow();
        var byPurpose = registry.findLatestByPurpose("FAILURE_INSIGHT").orElseThrow();

        assertThat(byId).isEqualTo(byPurpose);
        assertThat(byId)
            .returns("v2.failure-insight.v1", PromptTemplate::templateId)
            .returns("FAILURE_INSIGHT", PromptTemplate::purpose)
            .returns("v1", PromptTemplate::version)
            .returns(List.of("evidence", "taskId"), PromptTemplate::requiredVariables);
        assertThat(byId.outputConstraint()).contains("structured JSON");
    }

    @Test
    void templateVariablesRenderDeterministicallyWithOutputConstraint() {
        var template = new PromptTemplate(
            "custom.v2",
            "CUSTOM",
            "v2",
            "Task={{taskId}}\nEvidence={{evidence}}\nTask again={{taskId}}",
            List.of("taskId", "evidence"),
            "Return JSON."
        );

        var result = template.render(Map.of(
            "taskId", "task-123",
            "evidence", "HTTP 500"
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.renderedPrompt())
            .isEqualTo("""
                Task=task-123
                Evidence=HTTP 500
                Task again=task-123

                Output constraint: Return JSON.""");
        assertThat(result.errorType()).isEqualTo(LlmErrorType.NONE);
        assertThat(result.template().version()).isEqualTo("v2");
    }

    @Test
    void missingRequiredVariablesReturnTemplateRenderErrorWithoutCallingProvider() {
        var registry = new PromptTemplateRegistry(List.of(new PromptTemplate(
            "custom.missing.v1",
            "CUSTOM",
            "v1",
            "Explain {{failure}} for {{taskId}}",
            List.of("taskId", "failure"),
            "Return text."
        )));
        var providerCalls = new AtomicInteger();
        LlmProvider provider = new LlmProvider() {
            @Override
            public String providerName() {
                return "counting";
            }

            @Override
            public LlmResponse generate(LlmRequest request) {
                providerCalls.incrementAndGet();
                return LlmResponse.text("counting", request.model(), "should not happen", LlmTokenUsage.zero());
            }
        };

        var result = registry.renderById("custom.missing.v1", Map.of("taskId", "task-1"));
        if (result.success()) {
            provider.generate(LlmRequest.of("counting", "model", "CUSTOM", result.renderedPrompt()));
        }

        assertThat(result.success()).isFalse();
        assertThat(result.errorType()).isEqualTo(LlmErrorType.TEMPLATE_RENDER_ERROR);
        assertThat(result.errorMessage()).contains("failure");
        assertThat(result.missingVariables()).containsExactly("failure");
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void templateNotFoundReturnsStableRenderError() {
        var registry = new PromptTemplateRegistry();

        var result = registry.renderById("missing.template", Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.template()).isNull();
        assertThat(result.errorType()).isEqualTo(LlmErrorType.TEMPLATE_RENDER_ERROR);
        assertThat(result.errorMessage()).isEqualTo("Prompt template not found: missing.template");
    }
}
