package com.probeflow.testagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

public class ManualRealLlmProvider implements LlmProvider {

    private final ManualRealLlmProviderConfig config;
    private final ManualRealLlmClient client;
    private final ObjectMapper objectMapper;

    public ManualRealLlmProvider(ManualRealLlmProviderConfig config, ManualRealLlmClient client) {
        this(config, client, new ObjectMapper());
    }

    ManualRealLlmProvider(
        ManualRealLlmProviderConfig config,
        ManualRealLlmClient client,
        ObjectMapper objectMapper
    ) {
        this.config = config == null
            ? new ManualRealLlmProviderConfig(null, null, null, null, null, null, null)
            : config;
        this.client = client == null
            ? request -> {
                throw new ManualRealLlmClientException(
                    LlmErrorType.PROVIDER_ERROR,
                    "Manual real LLM client is not configured."
                );
            }
            : client;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public String providerName() {
        return config.providerName();
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        var missing = config.missingRequiredFields();
        if (!missing.isEmpty()) {
            throw new LlmProviderException(
                LlmErrorType.PROVIDER_ERROR,
                "Manual real LLM config missing required fields: " + missing
            );
        }
        try {
            var response = client.complete(new ManualRealLlmClientRequest(
                config.endpoint(),
                config.apiKey(),
                request.model(),
                request.purpose(),
                request.prompt(),
                config.timeoutMs(),
                config.maxTokens(),
                request.metadata()
            ));
            return parseResponse(request, response);
        } catch (ManualRealLlmClientException exception) {
            throw new LlmProviderException(
                exception.errorType(),
                exception.getMessage(),
                exception.providerTraceId(),
                exception
            );
        } catch (LlmProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LlmProviderException(
                LlmErrorType.PROVIDER_ERROR,
                "Manual real LLM provider failed: " + exception.getMessage(),
                null,
                exception
            );
        }
    }

    private LlmResponse parseResponse(LlmRequest request, ManualRealLlmClientResponse clientResponse) {
        var statusCode = clientResponse == null ? 0 : clientResponse.statusCode();
        var body = clientResponse == null ? "" : clientResponse.body();
        if (statusCode == 429) {
            throw providerError(LlmErrorType.RATE_LIMITED, "Manual real LLM provider rate limited the request.", clientResponse);
        }
        if (statusCode < 200 || statusCode >= 300) {
            throw providerError(
                LlmErrorType.PROVIDER_ERROR,
                "Manual real LLM provider returned HTTP status " + statusCode + ".",
                clientResponse
            );
        }
        try {
            var root = objectMapper.readTree(body);
            if (root.path("blocked").asBoolean(false)) {
                throw providerError(
                    LlmErrorType.POLICY_BLOCKED,
                    textOrDefault(root.path("message"), "Manual real LLM provider response was policy blocked."),
                    clientResponse
                );
            }
            var text = textOrDefault(root.path("text"), null);
            if (text == null) {
                text = root.path("choices").path(0).path("message").path("content").asText(null);
            }
            if (text == null || text.isBlank()) {
                throw providerError(
                    LlmErrorType.OUTPUT_PARSE_ERROR,
                    "Manual real LLM response did not include text or choices[0].message.content.",
                    clientResponse
                );
            }
            var usage = tokenUsage(root.path("usage"));
            var traceId = textOrDefault(root.path("id"), clientResponse.providerTraceId());
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("statusCode", statusCode);
            metadata.put("manualRealProvider", true);
            return new LlmResponse(
                providerName(),
                request.model(),
                text,
                usage,
                traceId,
                false,
                metadata
            );
        } catch (LlmProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw providerError(
                LlmErrorType.OUTPUT_PARSE_ERROR,
                "Manual real LLM response could not be parsed as structured JSON.",
                clientResponse
            );
        }
    }

    private LlmProviderException providerError(
        LlmErrorType errorType,
        String message,
        ManualRealLlmClientResponse clientResponse
    ) {
        return new LlmProviderException(
            errorType,
            message,
            clientResponse == null ? null : clientResponse.providerTraceId(),
            null
        );
    }

    private LlmTokenUsage tokenUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return LlmTokenUsage.zero();
        }
        var promptTokens = intField(usage, "promptTokens", "prompt_tokens");
        var completionTokens = intField(usage, "completionTokens", "completion_tokens");
        var totalTokens = intField(usage, "totalTokens", "total_tokens");
        return new LlmTokenUsage(promptTokens, completionTokens, totalTokens);
    }

    private int intField(JsonNode node, String camelCase, String snakeCase) {
        if (node.has(camelCase)) {
            return Math.max(0, node.path(camelCase).asInt());
        }
        if (node.has(snakeCase)) {
            return Math.max(0, node.path(snakeCase).asInt());
        }
        return 0;
    }

    private String textOrDefault(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        var value = node.asText();
        return value == null || value.isBlank() ? fallback : value;
    }
}
