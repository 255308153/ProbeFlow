package com.probeflow.testagent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

public class ManualRealEmbeddingProvider implements EmbeddingService {

    private static final Pattern BEARER_PATTERN = Pattern.compile(
        "(?i)(authorization\\s*:\\s*bearer\\s+)[^\\s,;\"']+"
    );
    private static final Pattern SECRET_QUERY_PATTERN = Pattern.compile(
        "(?i)(api[_-]?key|token|key)=([^\\s&\"']+)"
    );

    private final ManualRealEmbeddingProviderConfig config;
    private final ManualRealEmbeddingClient client;
    private final ObjectMapper objectMapper;

    public ManualRealEmbeddingProvider(ManualRealEmbeddingProviderConfig config, ManualRealEmbeddingClient client) {
        this(config, client, new ObjectMapper());
    }

    ManualRealEmbeddingProvider(
        ManualRealEmbeddingProviderConfig config,
        ManualRealEmbeddingClient client,
        ObjectMapper objectMapper
    ) {
        this.config = config == null
            ? new ManualRealEmbeddingProviderConfig(null, null, null, null, 0, 0, 0, null, null, 0, null)
            : config;
        this.client = client == null
            ? request -> {
                throw new ManualRealEmbeddingClientException(
                    EmbeddingFailureCode.MISSING_CONFIG,
                    "Manual real embedding client is not configured."
                );
            }
            : client;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public float[] embedDocument(String text) {
        return embed("document", text, config.documentPrefix());
    }

    @Override
    public float[] embedQuery(String text) {
        return embed("query", text, config.queryPrefix());
    }

    @Override
    public int dimensions() {
        return config.dimension();
    }

    @Override
    public EmbeddingProfile profile() {
        ensureConfigured();
        return config.profile();
    }

    private float[] embed(String usage, String text, String prefix) {
        ensureConfigured();
        var profile = config.profile();
        var normalized = EmbeddingValidation.requireText(usage, text, profile);
        var input = prefix + normalized;
        rejectOverBudget(usage, input, profile);
        try {
            var response = client.embed(new ManualRealEmbeddingClientRequest(
                config.endpoint(),
                config.apiKey(),
                config.model(),
                input,
                usage,
                config.timeoutMs(),
                config.maxInputTokens(),
                config.batchSize(),
                requestMetadata(usage, profile)
            ));
            return EmbeddingValidation.requireVector(
                usage,
                profile,
                parseVector(response),
                config.dimension()
            );
        } catch (ManualRealEmbeddingClientException exception) {
            throw embeddingFailure(exception.failureCode(), profile, exception.getMessage(), exception);
        } catch (EmbeddingException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw embeddingFailure(EmbeddingFailureCode.INVALID_RESPONSE, profile, exception.getMessage(), exception);
        }
    }

    private void ensureConfigured() {
        var missing = config.missingRequiredFields();
        if (!missing.isEmpty()) {
            throw new EmbeddingException(
                EmbeddingFailureCode.MISSING_CONFIG,
                config.profileId(),
                "Manual real embedding config missing required fields: " + missing
            );
        }
    }

    private void rejectOverBudget(String usage, String input, EmbeddingProfile profile) {
        if (roughTokenCount(input) > config.maxInputTokens()) {
            throw new EmbeddingException(
                EmbeddingFailureCode.INVALID_RESPONSE,
                profile.profileId(),
                usage + " embedding input exceeds maxInputTokens for profile " + profile.profileId()
            );
        }
    }

    private int roughTokenCount(String input) {
        if (input == null || input.isBlank()) {
            return 0;
        }
        return input.trim().split("\\s+").length;
    }

    private LinkedHashMap<String, Object> requestMetadata(String usage, EmbeddingProfile profile) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("profileId", profile.profileId());
        metadata.put("providerMode", profile.providerMode().name());
        metadata.put("usage", usage);
        metadata.put("failurePolicy", config.failurePolicy());
        return metadata;
    }

    private float[] parseVector(ManualRealEmbeddingClientResponse response) {
        var statusCode = response == null ? 0 : response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new EmbeddingException(
                EmbeddingFailureCode.REMOTE_ERROR,
                config.profileId(),
                "Manual real embedding provider returned HTTP status " + statusCode + ": "
                    + redact(response == null ? "" : response.body())
            );
        }
        try {
            var root = objectMapper.readTree(response.body());
            var embedding = embeddingNode(root);
            if (embedding == null || !embedding.isArray()) {
                throw new EmbeddingException(
                    EmbeddingFailureCode.INVALID_RESPONSE,
                    config.profileId(),
                    "Manual real embedding response did not include data[0].embedding."
                );
            }
            var vector = new float[embedding.size()];
            for (var index = 0; index < embedding.size(); index++) {
                var value = embedding.get(index);
                if (!value.isNumber()) {
                    throw new EmbeddingException(
                        EmbeddingFailureCode.INVALID_RESPONSE,
                        config.profileId(),
                        "Manual real embedding response contains a non-numeric vector value."
                    );
                }
                vector[index] = (float) value.asDouble();
            }
            return vector;
        } catch (EmbeddingException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new EmbeddingException(
                EmbeddingFailureCode.INVALID_RESPONSE,
                config.profileId(),
                "Manual real embedding response could not be parsed as structured JSON."
            );
        }
    }

    private JsonNode embeddingNode(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }
        if (root.path("embedding").isArray()) {
            return root.path("embedding");
        }
        return root.path("data").path(0).path("embedding");
    }

    private EmbeddingException embeddingFailure(
        EmbeddingFailureCode code,
        EmbeddingProfile profile,
        String message,
        RuntimeException cause
    ) {
        return new EmbeddingException(
            code,
            profile.profileId(),
            redact(message == null || message.isBlank() ? "Manual real embedding provider failed." : message)
        );
    }

    private String redact(String value) {
        if (value == null) {
            return "";
        }
        var redacted = value;
        if (config.apiKey() != null) {
            redacted = redacted.replace(config.apiKey(), "[REDACTED]");
        }
        if (config.endpoint() != null) {
            redacted = redacted.replace(config.endpoint(), endpointWithoutQuery(config.endpoint()));
        }
        redacted = BEARER_PATTERN.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = SECRET_QUERY_PATTERN.matcher(redacted).replaceAll("$1=[REDACTED]");
        return redacted;
    }

    private String endpointWithoutQuery(String endpoint) {
        var questionMark = endpoint.indexOf('?');
        return questionMark < 0 ? endpoint : endpoint.substring(0, questionMark);
    }
}
