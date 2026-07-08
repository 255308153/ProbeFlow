package com.probeflow.testagent.llm;

import com.probeflow.testagent.demorun.DemoRunLlmApplicationGateway;
import com.probeflow.testagent.demorun.DemoRunRealLlmGateway;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ManualRealLlmAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "probeflow.llm.manual-real", name = "enabled", havingValue = "true")
    ManualRealLlmProvider manualRealLlmProvider(
        ManualRealLlmProviderConfig config,
        ManualRealLlmClient client
    ) {
        return new ManualRealLlmProvider(config, client);
    }

    @Bean
    @ConditionalOnProperty(prefix = "probeflow.llm.manual-real", name = "enabled", havingValue = "true")
    ManualRealLlmProviderConfig manualRealLlmProviderConfig(
        @Value("${probeflow.llm.manual-real.provider-name:manual-real}") String providerName,
        @Value("${probeflow.llm.manual-real.endpoint:}") String endpoint,
        @Value("${probeflow.llm.manual-real.key:}") String apiKey,
        @Value("${probeflow.llm.manual-real.model:}") String model,
        @Value("${probeflow.llm.manual-real.timeout-ms:0}") int timeoutMs,
        @Value("${probeflow.llm.manual-real.max-tokens:0}") int maxTokens,
        @Value("${probeflow.llm.manual-real.cost-limit-cents:0}") int costLimitCents
    ) {
        return new ManualRealLlmProviderConfig(
            providerName,
            endpoint,
            apiKey,
            model,
            timeoutMs,
            maxTokens,
            costLimitCents
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "probeflow.llm.manual-real", name = "enabled", havingValue = "true")
    ManualRealLlmClient manualRealLlmClient(RestClient.Builder restClientBuilder) {
        return request -> {
            var body = new LinkedHashMap<String, Object>();
            body.put("model", request.model());
            body.put("input", request.prompt());
            body.put("max_tokens", request.maxTokens());
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
                return new ManualRealLlmClientResponse(
                    response.getStatusCode().value(),
                    response.getBody(),
                    firstHeader(response.getHeaders().toSingleValueMap(), "x-request-id")
                );
            } catch (org.springframework.web.client.ResourceAccessException exception) {
                if (exception.getMessage() != null && exception.getMessage().toLowerCase().contains("timed out")) {
                    throw new ManualRealLlmClientException(
                        LlmErrorType.TIMEOUT,
                        "Manual real LLM request timed out after "
                            + Duration.ofMillis(request.timeoutMs()).toMillis()
                            + " ms.",
                        null,
                        exception
                    );
                }
                throw new ManualRealLlmClientException(
                    LlmErrorType.NETWORK_ERROR,
                    "Manual real LLM network error: " + exception.getMessage(),
                    null,
                    exception
                );
            } catch (org.springframework.web.client.RestClientResponseException exception) {
                throw new ManualRealLlmClientException(
                    exception.getStatusCode().value() == 429 ? LlmErrorType.RATE_LIMITED : LlmErrorType.PROVIDER_ERROR,
                    "Manual real LLM provider returned HTTP status " + exception.getStatusCode().value() + ".",
                    exception.getResponseHeaders() == null
                        ? null
                        : firstHeader(exception.getResponseHeaders().toSingleValueMap(), "x-request-id"),
                    exception
                );
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "probeflow.llm.manual-real", name = "enabled", havingValue = "true")
    DemoRunRealLlmGateway demoRunRealLlmGateway(
        com.probeflow.testagent.llm.LlmApplicationService llm,
        ManualRealLlmProviderConfig config
    ) {
        return new DemoRunLlmApplicationGateway(llm, config);
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
