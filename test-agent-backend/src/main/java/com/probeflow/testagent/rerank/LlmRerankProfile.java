package com.probeflow.testagent.rerank;

public record LlmRerankProfile(
    boolean enabled,
    String profileId,
    String provider,
    String model,
    int maxSummaryChars
) {
    private static final String DEEPSEEK_V4_PRO = "deepseek-v4-pro";
    private static final int DEFAULT_MAX_SUMMARY_CHARS = 700;

    public LlmRerankProfile {
        profileId = clean(profileId, enabled ? "manual-llm-rerank" : "disabled");
        provider = clean(provider, enabled ? "manual-real" : "disabled");
        model = clean(model, enabled ? DEEPSEEK_V4_PRO : "disabled");
        maxSummaryChars = maxSummaryChars > 0 ? maxSummaryChars : DEFAULT_MAX_SUMMARY_CHARS;
    }

    public static LlmRerankProfile disabled() {
        return new LlmRerankProfile(false, "disabled", "disabled", "disabled", DEFAULT_MAX_SUMMARY_CHARS);
    }

    public static LlmRerankProfile manualDeepSeekV4Pro(String provider) {
        return new LlmRerankProfile(
            true,
            "manual-deepseek-v4-pro",
            provider,
            DEEPSEEK_V4_PRO,
            DEFAULT_MAX_SUMMARY_CHARS
        );
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
