package com.probeflow.testagent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class LlmProviderContractTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void providerAbstractionReturnsNormalizedStructuredResponse() {
        LlmProvider provider = new LlmProvider() {
            @Override
            public String providerName() {
                return "contract-provider";
            }

            @Override
            public LlmResponse generate(LlmRequest request) {
                return new LlmResponse(
                    providerName(),
                    request.model(),
                    "answer for " + request.purpose(),
                    LlmTokenUsage.of(8, 5),
                    "trace-123",
                    false,
                    Map.of("requestPurpose", request.purpose())
                );
            }
        };
        var request = new LlmRequest(
            "contract-provider",
            "model-a",
            "failure-insight",
            "Explain {{failure}}",
            "task-1",
            "step-1",
            Map.of("source", "unit-test")
        );

        var response = provider.generate(request);
        var result = LlmCallResult.success(response);

        assertThat(provider.providerName()).isEqualTo("contract-provider");
        assertThat(result)
            .returns(LlmCallStatus.SUCCESS, LlmCallResult::status)
            .returns(true, LlmCallResult::succeeded)
            .returns(false, LlmCallResult::fakeProvider);
        assertThat(result.response())
            .returns("contract-provider", LlmResponse::provider)
            .returns("model-a", LlmResponse::model)
            .returns("answer for failure-insight", LlmResponse::text)
            .returns("trace-123", LlmResponse::providerTraceId);
        assertThat(result.response().tokenUsage())
            .returns(8, LlmTokenUsage::promptTokens)
            .returns(5, LlmTokenUsage::completionTokens)
            .returns(13, LlmTokenUsage::totalTokens);
        assertThat(request.metadata()).containsEntry("source", "unit-test");
        assertThat(response.metadata()).containsEntry("requestPurpose", "failure-insight");
    }

    @Test
    void callResultRepresentsFailureBlockedAndSkippedStatesWithStableErrors() {
        var failed = LlmCallResult.failure(LlmErrorType.TIMEOUT, "provider timed out");
        var blocked = LlmCallResult.blocked(LlmErrorType.POLICY_BLOCKED, "real provider disabled");
        var skipped = LlmCallResult.skipped("no prompt required");

        assertThat(failed)
            .returns(LlmCallStatus.FAILED, LlmCallResult::status)
            .returns(LlmErrorType.TIMEOUT, LlmCallResult::errorType)
            .returns("provider timed out", LlmCallResult::errorMessage)
            .returns(true, LlmCallResult::failed);
        assertThat(blocked)
            .returns(LlmCallStatus.BLOCKED, LlmCallResult::status)
            .returns(LlmErrorType.POLICY_BLOCKED, LlmCallResult::errorType);
        assertThat(skipped)
            .returns(LlmCallStatus.SKIPPED, LlmCallResult::status)
            .returns(LlmErrorType.NONE, LlmCallResult::errorType);
    }

    @Test
    void requestResponseAndTokenUsageValidateRequiredFieldsAndDefensivelyCopyMetadata() {
        var metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("key", "value");
        var request = new LlmRequest(" provider ", " model ", " purpose ", null, " task ", " step ", metadata);
        metadata.put("key", "changed");

        assertThat(request)
            .returns("provider", LlmRequest::provider)
            .returns("model", LlmRequest::model)
            .returns("purpose", LlmRequest::purpose)
            .returns("", LlmRequest::prompt)
            .returns("task", LlmRequest::taskId)
            .returns("step", LlmRequest::planStepId);
        assertThat(request.metadata()).containsEntry("key", "value");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> LlmRequest.of(" ", "model", "purpose", "prompt"))
            .withMessage("provider is required");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> LlmResponse.text("provider", " ", "text", LlmTokenUsage.zero()))
            .withMessage("model is required");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> LlmTokenUsage.of(-1, 0))
            .withMessage("token usage cannot be negative");
    }

    @Test
    void llmDomainModelDoesNotDependOnOrchestrationInternals() throws Exception {
        var llmSources = Files.walk(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/llm"))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(this::readUnchecked)
            .collect(Collectors.joining("\n"));

        assertThat(llmSources)
            .doesNotContain("com.probeflow.testagent.orchestration")
            .doesNotContain("PlanStepRunner")
            .doesNotContain("TaskOrchestrationApplicationService")
            .doesNotContain("ToolRouter")
            .doesNotContain("HttpExecutionApplicationService");
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
