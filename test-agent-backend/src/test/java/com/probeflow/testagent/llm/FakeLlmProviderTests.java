package com.probeflow.testagent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FakeLlmProviderTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Autowired
    private LlmProvider llmProvider;

    @Test
    void fakeProviderIsTheOnlyLlmProviderBeanInTestProfileAndNeedsNoApiKey() {
        assertThat(llmProvider).isInstanceOf(FakeLlmProvider.class);
        assertThat(llmProvider.providerName()).isEqualTo("fake");
    }

    @Test
    void fakeProviderReturnsDeterministicSuccessfulResponsesAndTokenUsage() {
        var request = LlmRequest.of("fake", "fake-model", "report-summary", "Summarize failed checkout API");

        var first = llmProvider.generate(request);
        var second = llmProvider.generate(request);
        var result = LlmCallResult.success(first);

        assertThat(first).isEqualTo(second);
        assertThat(first)
            .returns("fake", LlmResponse::provider)
            .returns("fake-model", LlmResponse::model)
            .returns(true, LlmResponse::fakeProvider);
        assertThat(first.text()).contains("Fake LLM response", "purpose=report-summary");
        assertThat(first.providerTraceId()).startsWith("fake-");
        assertThat(first.tokenUsage())
            .returns(4, LlmTokenUsage::promptTokens)
            .extracting(LlmTokenUsage::totalTokens)
            .isEqualTo(first.tokenUsage().promptTokens() + first.tokenUsage().completionTokens());
        assertThat(result.fakeProvider()).isTrue();
    }

    @Test
    void fakeProviderSupportsCannedResponsesByPurposeOrExplicitResponseKey() {
        var provider = new FakeLlmProvider()
            .withResponse("planner", "fixed planner answer")
            .withResponse("template.v1", "fixed template answer");

        var byPurpose = provider.generate(LlmRequest.of("fake", "fake-model", "planner", "Plan safely"));
        var byTemplateKey = provider.generate(new LlmRequest(
            "fake",
            "fake-model",
            "planner",
            "Plan safely",
            null,
            null,
            Map.of(FakeLlmProvider.RESPONSE_KEY_METADATA, "template.v1")
        ));

        assertThat(byPurpose.text()).isEqualTo("fixed planner answer");
        assertThat(byTemplateKey.text()).isEqualTo("fixed template answer");
        assertThat(byTemplateKey.metadata()).containsEntry("responseKey", "template.v1");
    }

    @Test
    void fakeProviderCanSimulateCommonProviderErrorsWithoutNetwork() {
        var provider = new FakeLlmProvider()
            .withError("timeout", LlmErrorType.TIMEOUT)
            .withError("rate-limit", LlmErrorType.RATE_LIMITED)
            .withError("provider", LlmErrorType.PROVIDER_ERROR)
            .withError("parse", LlmErrorType.OUTPUT_PARSE_ERROR);

        assertThatThrownBy(() -> provider.generate(LlmRequest.of("fake", "fake-model", "timeout", "slow")))
            .isInstanceOf(LlmProviderException.class)
            .extracting(exception -> ((LlmProviderException) exception).errorType())
            .isEqualTo(LlmErrorType.TIMEOUT);
        assertThatThrownBy(() -> provider.generate(LlmRequest.of("fake", "fake-model", "rate-limit", "busy")))
            .isInstanceOf(LlmProviderException.class)
            .extracting(exception -> ((LlmProviderException) exception).errorType())
            .isEqualTo(LlmErrorType.RATE_LIMITED);
        assertThatThrownBy(() -> provider.generate(LlmRequest.of("fake", "fake-model", "provider", "boom")))
            .isInstanceOf(LlmProviderException.class)
            .extracting(exception -> ((LlmProviderException) exception).errorType())
            .isEqualTo(LlmErrorType.PROVIDER_ERROR);
        assertThatThrownBy(() -> provider.generate(LlmRequest.of("fake", "fake-model", "parse", "bad json")))
            .isInstanceOf(LlmProviderException.class)
            .extracting(exception -> ((LlmProviderException) exception).errorType())
            .isEqualTo(LlmErrorType.OUTPUT_PARSE_ERROR);
    }

    @Test
    void fakeProviderSourceHasNoRealNetworkOrSdkDependency() throws Exception {
        var llmSources = Files.walk(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/llm"))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(this::readUnchecked)
            .collect(Collectors.joining("\n"));

        assertThat(llmSources)
            .doesNotContain("OpenAI")
            .doesNotContain("Anthropic")
            .doesNotContain("ChatModel")
            .doesNotContain("WebClient")
            .doesNotContain("RestTemplate")
            .doesNotContain("java.net.http.HttpClient")
            .doesNotContain("HttpURLConnection")
            .doesNotContain("Socket")
            .doesNotContain("API_KEY");
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
