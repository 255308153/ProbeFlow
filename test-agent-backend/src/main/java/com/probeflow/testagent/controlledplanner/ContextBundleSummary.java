package com.probeflow.testagent.controlledplanner;

import java.util.List;

public record ContextBundleSummary(
    String summary,
    List<String> citationRefs,
    int knowledgeHitCount,
    int memoryItemCount,
    int tokenBudget
) {

    private static final int MAX_SUMMARY_LENGTH = 1_200;

    public ContextBundleSummary {
        summary = compact(summary, MAX_SUMMARY_LENGTH);
        citationRefs = citationRefs == null ? List.of() : citationRefs.stream()
            .map(ContextBundleSummary::clean)
            .filter(value -> value != null)
            .distinct()
            .sorted()
            .toList();
        knowledgeHitCount = Math.max(0, knowledgeHitCount);
        memoryItemCount = Math.max(0, memoryItemCount);
        tokenBudget = Math.max(0, tokenBudget);
    }

    public static ContextBundleSummary empty() {
        return new ContextBundleSummary("", List.of(), 0, 0, 0);
    }

    public static ContextBundleSummary of(
        String summary,
        List<String> citationRefs,
        int knowledgeHitCount,
        int memoryItemCount,
        int tokenBudget
    ) {
        return new ContextBundleSummary(summary, citationRefs, knowledgeHitCount, memoryItemCount, tokenBudget);
    }

    private static String compact(String value, int maxLength) {
        var cleaned = clean(value);
        if (cleaned == null) {
            return "";
        }
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, maxLength).trim() + "...";
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
