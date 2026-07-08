package com.probeflow.testagent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ManualRealLlmProviderIssue04Tests {

    private final ManualRealLlmProviderConfig completeConfig = new ManualRealLlmProviderConfig(
        "manual-real",
        "https://llm.example.test/v1/responses",
        "sk-test-secret",
        "real-model",
        2_000,
        512,
        100
    );

    @Test
    void adapterImplementsProviderContractAndParsesStructuredResponseWithoutNetwork() {
        var provider = new ManualRealLlmProvider(completeConfig, request -> {
            assertThat(request.endpoint()).isEqualTo("https://llm.example.test/v1/responses");
            assertThat(request.apiKey()).isEqualTo("sk-test-secret");
            assertThat(request.model()).isEqualTo("real-model");
            assertThat(request.purpose()).isEqualTo("V4_DEMO");
            return new ManualRealLlmClientResponse(
                200,
                """
                    {
                      "id": "trace-real-1",
                      "text": "real structured answer",
                      "usage": {
                        "prompt_tokens": 7,
                        "completion_tokens": 5,
                        "total_tokens": 12
                      }
                    }
                    """,
                "trace-header"
            );
        });

        var response = provider.generate(new LlmRequest(
            "manual-real",
            "real-model",
            "V4_DEMO",
            "Summarize demo run",
            "task-1",
            "step-1",
            Map.of("fixtureId", "order-suite-demo")
        ));

        assertThat(provider.providerName()).isEqualTo("manual-real");
        assertThat(response)
            .returns("manual-real", LlmResponse::provider)
            .returns("real-model", LlmResponse::model)
            .returns("real structured answer", LlmResponse::text)
            .returns(false, LlmResponse::fakeProvider)
            .returns("trace-real-1", LlmResponse::providerTraceId);
        assertThat(response.tokenUsage())
            .returns(7, LlmTokenUsage::promptTokens)
            .returns(5, LlmTokenUsage::completionTokens)
            .returns(12, LlmTokenUsage::totalTokens);
    }

    @Test
    void missingConfigIsClassifiedAsProviderErrorBeforeClientAccess() {
        var provider = new ManualRealLlmProvider(
            new ManualRealLlmProviderConfig("manual-real", null, null, null, null, null, null),
            request -> {
                throw new AssertionError("client must not be called when config is incomplete");
            }
        );

        assertThatThrownBy(() -> provider.generate(LlmRequest.of("manual-real", "real-model", "V4_DEMO", "prompt")))
            .isInstanceOf(LlmProviderException.class)
            .satisfies(exception -> {
                var providerException = (LlmProviderException) exception;
                assertThat(providerException.errorType()).isEqualTo(LlmErrorType.PROVIDER_ERROR);
                assertThat(providerException.getMessage())
                    .contains("endpoint", "key", "model", "timeoutMs", "maxTokens", "costLimitCents");
            });
    }

    @Test
    void timeoutExternalErrorInvalidOutputAndPolicyBlockAreClassified() {
        assertProviderFailure(
            request -> {
                throw new ManualRealLlmClientException(LlmErrorType.TIMEOUT, "client timed out");
            },
            LlmErrorType.TIMEOUT
        );
        assertProviderFailure(
            request -> {
                throw new ManualRealLlmClientException(LlmErrorType.NETWORK_ERROR, "network unavailable");
            },
            LlmErrorType.NETWORK_ERROR
        );
        assertProviderFailure(
            request -> new ManualRealLlmClientResponse(200, "{not-json", "trace-parse"),
            LlmErrorType.OUTPUT_PARSE_ERROR
        );
        assertProviderFailure(
            request -> new ManualRealLlmClientResponse(
                200,
                "{\"blocked\":true,\"message\":\"blocked by provider policy\"}",
                "trace-policy"
            ),
            LlmErrorType.POLICY_BLOCKED
        );
    }

    private void assertProviderFailure(ManualRealLlmClient client, LlmErrorType expected) {
        var provider = new ManualRealLlmProvider(completeConfig, client);

        assertThatThrownBy(() -> provider.generate(LlmRequest.of("manual-real", "real-model", "V4_DEMO", "prompt")))
            .isInstanceOf(LlmProviderException.class)
            .extracting(exception -> ((LlmProviderException) exception).errorType())
            .isEqualTo(expected);
    }
}
