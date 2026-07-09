package com.probeflow.testagent.retrieval;

import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.memory.MemoryFactType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StageRoutingProfile(
    String stageProfile,
    List<RetrievalRoute> enabledRoutes,
    Map<RetrievalRoute, Double> routeWeights,
    List<DocumentType> documentTypePreferences,
    List<MemoryFactType> factTypePreferences,
    Map<RetrievalRoute, Integer> perRouteLimits,
    String fallbackStrategy,
    String diagnostic,
    RoutingBudgetPolicy budgetPolicy
) {
    public StageRoutingProfile {
        Objects.requireNonNull(stageProfile, "stageProfile must not be null");
        enabledRoutes = enabledRoutes == null ? List.of() : List.copyOf(enabledRoutes);
        routeWeights = immutableEnumMap(routeWeights);
        documentTypePreferences = documentTypePreferences == null ? List.of() : List.copyOf(documentTypePreferences);
        factTypePreferences = factTypePreferences == null ? List.of() : List.copyOf(factTypePreferences);
        perRouteLimits = immutableLimitMap(perRouteLimits);
        fallbackStrategy = fallbackStrategy == null ? "fallback-to-original-semantic" : fallbackStrategy;
        diagnostic = diagnostic == null ? "" : diagnostic;
        budgetPolicy = budgetPolicy == null ? RoutingBudgetPolicy.conservativeDefault() : budgetPolicy;
    }

    public boolean enables(RetrievalRoute route) {
        return enabledRoutes.contains(route);
    }

    public int limitFor(RetrievalRoute route) {
        return perRouteLimits.getOrDefault(route, 4);
    }

    public double weightFor(RetrievalRoute route) {
        return routeWeights.getOrDefault(route, 0.0d);
    }

    private static Map<RetrievalRoute, Double> immutableEnumMap(Map<RetrievalRoute, Double> values) {
        var normalized = new EnumMap<RetrievalRoute, Double>(RetrievalRoute.class);
        if (values != null) {
            values.forEach((route, weight) -> {
                if (route != null && weight != null && weight > 0.0d) {
                    normalized.put(route, weight);
                }
            });
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static Map<RetrievalRoute, Integer> immutableLimitMap(Map<RetrievalRoute, Integer> values) {
        var normalized = new EnumMap<RetrievalRoute, Integer>(RetrievalRoute.class);
        if (values != null) {
            values.forEach((route, limit) -> {
                if (route != null && limit != null && limit > 0) {
                    normalized.put(route, limit);
                }
            });
        }
        return Collections.unmodifiableMap(normalized);
    }
}
