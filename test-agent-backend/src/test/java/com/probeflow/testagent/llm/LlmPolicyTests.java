package com.probeflow.testagent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmPolicyTests {

    @Test
    void fakeOnlyPolicyAllowsFakeProviderWithoutRealApiKey() {
        var policy = new LlmPolicy(false, "fake");

        var decision = policy.evaluate(LlmExecutionOptions.of("fake", "fake-model"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.errorType()).isEqualTo(LlmErrorType.NONE);
    }

    @Test
    void fakeOnlyPolicyBlocksRealProvider() {
        var policy = new LlmPolicy(false, "fake");

        var decision = policy.evaluate(LlmExecutionOptions.of("openai", "gpt-4.1-mini"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.errorType()).isEqualTo(LlmErrorType.POLICY_BLOCKED);
        assertThat(decision.message()).contains("not allowed");
    }

    @Test
    void realProviderRequiresExplicitAllowAndProviderAllowList() {
        var blocked = new LlmPolicy(false, "fake,openai")
            .evaluate(LlmExecutionOptions.of("openai", "gpt-4.1-mini"));
        var allowed = new LlmPolicy(true, "fake,openai")
            .evaluate(LlmExecutionOptions.of("openai", "gpt-4.1-mini"));

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.message()).contains("disabled");
        assertThat(allowed.allowed()).isTrue();
    }
}
