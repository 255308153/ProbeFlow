package com.probeflow.testagent.knowledge;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

@Configuration
public class ManualRealEmbeddingAutoConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "probeflow.embedding.manual-real", name = "enabled", havingValue = "true")
    ManualRealEmbeddingProvider manualRealEmbeddingProvider(
        ManualRealEmbeddingProviderConfig config,
        ManualRealEmbeddingClient client
    ) {
        return new ManualRealEmbeddingProvider(config, client);
    }

    @Bean
    @ConditionalOnProperty(prefix = "probeflow.embedding.manual-real", name = "enabled", havingValue = "true")
    ManualRealEmbeddingProviderConfig manualRealEmbeddingProviderConfig(
        @Value("${probeflow.embedding.manual-real.profile-id:manual-real-embedding}") String profileId,
        @Value("${probeflow.embedding.manual-real.endpoint:}") String endpoint,
        @Value("${probeflow.embedding.manual-real.key:}") String apiKey,
        @Value("${probeflow.embedding.manual-real.model:}") String model,
        @Value("${probeflow.embedding.manual-real.dimension:0}") int dimension,
        @Value("${probeflow.embedding.manual-real.timeout-ms:0}") int timeoutMs,
        @Value("${probeflow.embedding.manual-real.max-input-tokens:0}") int maxInputTokens,
        @Value("${probeflow.embedding.manual-real.query-prefix:}") String queryPrefix,
        @Value("${probeflow.embedding.manual-real.document-prefix:}") String documentPrefix,
        @Value("${probeflow.embedding.manual-real.batch-size:0}") int batchSize,
        @Value("${probeflow.embedding.manual-real.failure-policy:}") String failurePolicy
    ) {
        return new ManualRealEmbeddingProviderConfig(
            profileId,
            endpoint,
            apiKey,
            model,
            dimension,
            timeoutMs,
            maxInputTokens,
            queryPrefix,
            documentPrefix,
            batchSize,
            failurePolicy
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "probeflow.embedding.manual-real", name = "enabled", havingValue = "true")
    ManualRealEmbeddingClient manualRealEmbeddingClient(RestClient.Builder restClientBuilder) {
        return request -> {
            var body = new LinkedHashMap<String, Object>();
            body.put("model", request.model());
            body.put("input", request.input());
            body.put("metadata", request.metadata());
            try {
                var response = restClientBuilder
                    .baseUrl(request.endpoint())
                    .build()
                    .post()
                    .uri("")
                    .headers(headers -> headers.setBearerAuth(request.apiKey()))
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
                return new ManualRealEmbeddingClientResponse(
                    response.getStatusCode().value(),
                    response.getBody(),
                    firstHeader(response.getHeaders().toSingleValueMap(), "x-request-id")
                );
            } catch (org.springframework.web.client.ResourceAccessException exception) {
                if (exception.getMessage() != null && exception.getMessage().toLowerCase().contains("timed out")) {
                    throw new ManualRealEmbeddingClientException(
                        EmbeddingFailureCode.TIMEOUT,
                        "Manual real embedding request timed out after "
                            + Duration.ofMillis(request.timeoutMs()).toMillis()
                            + " ms.",
                        null,
                        exception
                    );
                }
                throw new ManualRealEmbeddingClientException(
                    EmbeddingFailureCode.REMOTE_ERROR,
                    "Manual real embedding network error: " + exception.getMessage(),
                    null,
                    exception
                );
            } catch (org.springframework.web.client.RestClientResponseException exception) {
                throw new ManualRealEmbeddingClientException(
                    EmbeddingFailureCode.REMOTE_ERROR,
                    "Manual real embedding provider returned HTTP status "
                        + exception.getStatusCode().value()
                        + ".",
                    exception.getResponseHeaders() == null
                        ? null
                        : firstHeader(exception.getResponseHeaders().toSingleValueMap(), "x-request-id"),
                    exception
                );
            }
        };
    }

    private String firstHeader(Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        return headers.entrySet().stream()
            .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }
}
