package com.probeflow.testagent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LlmApplicationServiceTests {

    @Autowired
    private LlmApplicationService llm;

    @Autowired
    private LlmCallLogRepository logs;

    @Autowired
    private EntityManager entityManager;

    @MockBean
    private LlmProvider provider;

    @Test
    void successfulCallRendersTemplateInvokesProviderAndPersistsSuccessLog() {
        when(provider.providerName()).thenReturn("fake");
        when(provider.generate(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            return new LlmResponse(
                "fake",
                request.model(),
                "structured response",
                LlmTokenUsage.of(9, 3),
                "trace-success",
                true,
                Map.of("observedPurpose", request.purpose())
            );
        });

        var result = llm.call(LlmCallRequest.forTemplate(
            "task-llm-success",
            "step-llm-success",
            "v2.failure-insight.v1",
            Map.of("taskId", "task-llm-success", "evidence", "HTTP 500 from POST /orders"),
            "fake",
            "fake-model"
        ));
        entityManager.flush();
        entityManager.clear();

        assertThat(result.succeeded()).isTrue();
        assertThat(result.callResult().response().text()).isEqualTo("structured response");
        assertThat(result.requestHash()).hasSize(64);
        assertThat(result.templateId()).isEqualTo("v2.failure-insight.v1");
        assertThat(result.templateVersion()).isEqualTo("v1");
        verify(provider).generate(any(LlmRequest.class));

        var log = logs.findById(result.llmCallId()).orElseThrow();
        assertThat(log.getTaskId()).isEqualTo("task-llm-success");
        assertThat(log.getPlanStepId()).isEqualTo("step-llm-success");
        assertThat(log.getPurpose()).isEqualTo("FAILURE_INSIGHT");
        assertThat(log.getProvider()).isEqualTo("fake");
        assertThat(log.getModel()).isEqualTo("fake-model");
        assertThat(log.getTemplateId()).isEqualTo("v2.failure-insight.v1");
        assertThat(log.getTemplateVersion()).isEqualTo("v1");
        assertThat(log.getRequestHash()).isEqualTo(result.requestHash());
        assertThat(log.getStatus()).isEqualTo(LlmCallStatus.SUCCESS);
        assertThat(log.getErrorType()).isEqualTo(LlmErrorType.NONE);
        assertThat(log.getLatencyMs()).isNotNegative();
        assertThat(log.getPromptTokens()).isEqualTo(9);
        assertThat(log.getCompletionTokens()).isEqualTo(3);
        assertThat(log.getTotalTokens()).isEqualTo(12);
        assertThat(log.getPromptSummary()).contains("HTTP 500 from POST /orders");
        assertThat(log.getResponseSummary()).isEqualTo("structured response");
        assertThat(log.getProviderTraceId()).isEqualTo("trace-success");
        assertThat(log.isFakeProvider()).isTrue();
    }

    @Test
    void providerFailureReturnsFailureResultAndPersistsFailureLog() {
        when(provider.providerName()).thenReturn("fake");
        when(provider.generate(any())).thenThrow(new LlmProviderException(
            LlmErrorType.RATE_LIMITED,
            "fake rate limited",
            "trace-failure",
            null
        ));

        var result = llm.call(LlmCallRequest.forTemplate(
            "task-llm-failure",
            "step-llm-failure",
            "v2.report-narrative.v1",
            Map.of("taskId", "task-llm-failure", "summary", "failed=2"),
            "fake",
            "fake-model"
        ));
        entityManager.flush();
        entityManager.clear();

        assertThat(result.succeeded()).isFalse();
        assertThat(result.callResult())
            .returns(LlmCallStatus.FAILED, LlmCallResult::status)
            .returns(LlmErrorType.RATE_LIMITED, LlmCallResult::errorType)
            .returns("fake rate limited", LlmCallResult::errorMessage);
        verify(provider).generate(any(LlmRequest.class));

        var log = logs.findById(result.llmCallId()).orElseThrow();
        assertThat(log.getStatus()).isEqualTo(LlmCallStatus.FAILED);
        assertThat(log.getErrorType()).isEqualTo(LlmErrorType.RATE_LIMITED);
        assertThat(log.getErrorMessage()).isEqualTo("fake rate limited");
        assertThat(log.getProviderTraceId()).isEqualTo("trace-failure");
        assertThat(log.getPromptSummary()).contains("failed=2");
        assertThat(log.getResponseSummary()).isNull();
        assertThat(log.getTotalTokens()).isZero();
    }

    @Test
    void templateRenderFailureDoesNotCallProviderAndPersistsTemplateErrorLog() {
        when(provider.providerName()).thenReturn("fake");

        var result = llm.call(LlmCallRequest.forTemplate(
            "task-llm-render-error",
            "step-llm-render-error",
            "v2.failure-insight.v1",
            Map.of("taskId", "task-llm-render-error"),
            "fake",
            "fake-model"
        ));
        entityManager.flush();
        entityManager.clear();

        assertThat(result.succeeded()).isFalse();
        assertThat(result.callResult())
            .returns(LlmCallStatus.FAILED, LlmCallResult::status)
            .returns(LlmErrorType.TEMPLATE_RENDER_ERROR, LlmCallResult::errorType);
        assertThat(result.callResult().errorMessage()).contains("evidence");
        verify(provider, never()).generate(any());

        var log = logs.findById(result.llmCallId()).orElseThrow();
        assertThat(log.getStatus()).isEqualTo(LlmCallStatus.FAILED);
        assertThat(log.getErrorType()).isEqualTo(LlmErrorType.TEMPLATE_RENDER_ERROR);
        assertThat(log.getTemplateId()).isEqualTo("v2.failure-insight.v1");
        assertThat(log.getTemplateVersion()).isEqualTo("v1");
        assertThat(log.getPromptSummary()).isEmpty();
        assertThat(log.getTotalTokens()).isZero();
        assertThat(log.getMetadata()).containsEntry("templateRenderSuccess", false);
    }

    @Test
    void policyBlockedCallDoesNotAccessProviderAndPersistsAuditLog() {
        var result = llm.call(LlmCallRequest.forTemplate(
            "task-llm-policy",
            "step-llm-policy",
            "v2.context-gap-question.v1",
            Map.of("taskId", "task-llm-policy", "gap", "missing OAuth scope evidence"),
            new LlmExecutionOptions("openai", "gpt-4.1-mini", 0.1d, 256, 2_000, 1)
        ));
        entityManager.flush();
        entityManager.clear();

        assertThat(result.succeeded()).isFalse();
        assertThat(result.callResult())
            .returns(LlmCallStatus.BLOCKED, LlmCallResult::status)
            .returns(LlmErrorType.POLICY_BLOCKED, LlmCallResult::errorType);
        assertThat(result.callResult().errorMessage()).contains("policy");
        verify(provider, never()).generate(any());

        var log = logs.findById(result.llmCallId()).orElseThrow();
        assertThat(log.getStatus()).isEqualTo(LlmCallStatus.BLOCKED);
        assertThat(log.getErrorType()).isEqualTo(LlmErrorType.POLICY_BLOCKED);
        assertThat(log.getProvider()).isEqualTo("openai");
        assertThat(log.getModel()).isEqualTo("gpt-4.1-mini");
        assertThat(log.getTotalTokens()).isZero();
        assertThat(log.getMetadata())
            .containsEntry("temperature", 0.1d)
            .containsEntry("maxTokens", 256)
            .containsEntry("timeoutMs", 2_000)
            .containsEntry("retryAttempts", 1);
    }
}
