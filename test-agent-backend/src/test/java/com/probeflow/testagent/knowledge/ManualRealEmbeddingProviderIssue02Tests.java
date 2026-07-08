package com.probeflow.testagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManualRealEmbeddingProviderIssue02Tests {

    private final ManualRealEmbeddingProviderConfig completeConfig = new ManualRealEmbeddingProviderConfig(
        "manual-real-embedding",
        "https://embedding.example.test/v1/embeddings?token=endpoint-secret",
        "provider-secret-key",
        "bge-large-zh",
        4,
        2_000,
        128,
        "query: ",
        "document: ",
        16,
        "fail-fast"
    );

    @Test
    void buildsQueryAndDocumentRequestsWithSeparatePrefixesAndExposesRealProfile() {
        var requests = new ArrayList<ManualRealEmbeddingClientRequest>();
        var provider = new ManualRealEmbeddingProvider(completeConfig, request -> {
            requests.add(request);
            return new ManualRealEmbeddingClientResponse(200, "{\"data\":[{\"embedding\":[0.1,0.2,0.3,0.4]}]}", "trace-real");
        });

        var query = provider.embedQuery("payment timeout");
        var document = provider.embedDocument("payment timeout");

        assertThat(provider.profile())
            .returns(EmbeddingProviderMode.REAL, EmbeddingProfile::providerMode)
            .returns("manual-real-embedding", EmbeddingProfile::profileId)
            .returns("bge-large-zh", EmbeddingProfile::model)
            .returns(4, EmbeddingProfile::dimension)
            .returns("manual-profile", EmbeddingProfile::configSource)
            .returns("query: ", EmbeddingProfile::queryPrefix)
            .returns("document: ", EmbeddingProfile::documentPrefix);
        assertThat(query).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(document).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).input()).isEqualTo("query: payment timeout");
        assertThat(requests.get(0).usage()).isEqualTo("query");
        assertThat(requests.get(1).input()).isEqualTo("document: payment timeout");
        assertThat(requests.get(1).usage()).isEqualTo("document");
        assertThat(requests.get(1).input()).doesNotContain("query: ");
    }

    @Test
    void missingConfigReturnsClearDomainErrorWithoutSecretsOrExternalAccess() {
        var provider = new ManualRealEmbeddingProvider(
            new ManualRealEmbeddingProviderConfig("manual-real", "", "secret-key", "", 0, 0, 0, null, null, 0, null),
            request -> {
                throw new AssertionError("real embedding client must not be called when config is missing");
            }
        );

        assertThatThrownBy(() -> provider.embedQuery("payment timeout"))
            .isInstanceOf(EmbeddingException.class)
            .satisfies(exception -> {
                var embeddingException = (EmbeddingException) exception;
                assertThat(embeddingException.getFailureCode()).isEqualTo(EmbeddingFailureCode.MISSING_CONFIG);
                assertThat(embeddingException.getMessage())
                    .contains("endpoint", "model", "dimension", "timeoutMs", "maxInputTokens", "batchSize", "failurePolicy")
                    .doesNotContain("secret-key");
            });
    }

    @Test
    void classifiesClientAndInvalidResponseErrorsWithRedactedMessages() {
        assertProviderFailure(
            request -> {
                throw new ManualRealEmbeddingClientException(
                    EmbeddingFailureCode.TIMEOUT,
                    "timeout calling Authorization: Bearer provider-secret-key"
                );
            },
            EmbeddingFailureCode.TIMEOUT
        );
        assertProviderFailure(
            request -> new ManualRealEmbeddingClientResponse(503, "{\"error\":\"api_key=provider-secret-key\"}", "trace-503"),
            EmbeddingFailureCode.REMOTE_ERROR
        );
        assertProviderFailure(
            request -> new ManualRealEmbeddingClientResponse(200, "{\"data\":[{\"embedding\":[]}]}", "trace-empty"),
            EmbeddingFailureCode.EMPTY_VECTOR
        );
        assertProviderFailure(
            request -> new ManualRealEmbeddingClientResponse(200, "{\"data\":[{\"embedding\":[0.1,0.2]}]}", "trace-dim"),
            EmbeddingFailureCode.DIMENSION_MISMATCH
        );
        assertProviderFailure(
            request -> new ManualRealEmbeddingClientResponse(200, "{\"data\":[{\"embedding\":[0.1,\"nan\",0.3,0.4]}]}", "trace-invalid"),
            EmbeddingFailureCode.INVALID_RESPONSE
        );
    }

    private void assertProviderFailure(ManualRealEmbeddingClient client, EmbeddingFailureCode expectedCode) {
        var provider = new ManualRealEmbeddingProvider(completeConfig, client);

        assertThatThrownBy(() -> provider.embedQuery("payment timeout"))
            .isInstanceOf(EmbeddingException.class)
            .satisfies(exception -> {
                var embeddingException = (EmbeddingException) exception;
                assertThat(embeddingException.getFailureCode()).isEqualTo(expectedCode);
                assertThat(embeddingException.getMessage())
                    .doesNotContain("provider-secret-key")
                    .doesNotContain("Bearer provider-secret-key")
                    .doesNotContain("api_key=provider-secret-key");
            });
    }

    @Test
    void rejectsOverBudgetInputBeforeCallingClient() {
        var provider = new ManualRealEmbeddingProvider(
            new ManualRealEmbeddingProviderConfig(
                "manual-real-embedding",
                "https://embedding.example.test/v1/embeddings",
                "provider-secret-key",
                "bge-large-zh",
                4,
                2_000,
                4,
                "query: ",
                "document: ",
                16,
                "fail-fast"
            ),
            request -> {
                throw new AssertionError("over-budget input should be rejected before client call");
            }
        );

        assertThatThrownBy(() -> provider.embedDocument(String.join(" ", List.of(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"
        ))))
            .isInstanceOf(EmbeddingException.class)
            .extracting("failureCode")
            .isEqualTo(EmbeddingFailureCode.INVALID_RESPONSE);
    }
}
