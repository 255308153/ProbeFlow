package com.probeflow.testagent.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FakeLlmProvider implements LlmProvider {

    public static final String PROVIDER_NAME = "fake";
    public static final String RESPONSE_KEY_METADATA = "fakeResponseKey";

    private final Map<String, String> responsesByKey;
    private final Map<String, LlmErrorType> errorsByKey;

    public FakeLlmProvider() {
        this(Map.of(), Map.of());
    }

    public FakeLlmProvider(Map<String, String> responsesByKey, Map<String, LlmErrorType> errorsByKey) {
        this.responsesByKey = copyStringMap(responsesByKey);
        this.errorsByKey = errorsByKey == null ? Map.of() : Map.copyOf(errorsByKey);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        var responseKey = responseKey(request);
        var errorType = errorsByKey.get(responseKey);
        if (errorType != null && errorType != LlmErrorType.NONE) {
            throw new LlmProviderException(errorType, "Fake LLM simulated " + errorType + " for " + responseKey);
        }
        var text = responsesByKey.getOrDefault(responseKey, defaultResponseText(request, responseKey));
        return new LlmResponse(
            providerName(),
            request.model(),
            text,
            LlmTokenUsage.of(countTokens(request.prompt()), countTokens(text)),
            "fake-" + stableHash(responseKey + "\n" + request.prompt()).substring(0, 12),
            true,
            Map.of(
                "responseKey", responseKey,
                "deterministic", true
            )
        );
    }

    public FakeLlmProvider withResponse(String responseKey, String responseText) {
        var nextResponses = new LinkedHashMap<>(responsesByKey);
        nextResponses.put(cleanKey(responseKey), responseText == null ? "" : responseText);
        return new FakeLlmProvider(nextResponses, errorsByKey);
    }

    public FakeLlmProvider withError(String responseKey, LlmErrorType errorType) {
        var nextErrors = new LinkedHashMap<>(errorsByKey);
        nextErrors.put(cleanKey(responseKey), errorType == null ? LlmErrorType.PROVIDER_ERROR : errorType);
        return new FakeLlmProvider(responsesByKey, nextErrors);
    }

    private String responseKey(LlmRequest request) {
        var explicit = request.metadata().get(RESPONSE_KEY_METADATA);
        if (explicit instanceof String explicitKey && !explicitKey.isBlank()) {
            return explicitKey.trim();
        }
        return request.purpose();
    }

    private String defaultResponseText(LlmRequest request, String responseKey) {
        return "Fake LLM response"
            + " provider=" + providerName()
            + " model=" + request.model()
            + " purpose=" + request.purpose()
            + " key=" + responseKey
            + " hash=" + stableHash(request.prompt()).substring(0, 16);
    }

    private int countTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    private String stableHash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private static Map<String, String> copyStringMap(Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        var copy = new LinkedHashMap<String, String>();
        input.forEach((key, value) -> copy.put(cleanKey(key), value == null ? "" : value));
        return Map.copyOf(copy);
    }

    private static String cleanKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("fake response key is required");
        }
        return key.trim();
    }
}
