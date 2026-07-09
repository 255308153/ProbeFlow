package com.probeflow.testagent.rerank;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RerankRouteEvidence(
    List<String> queryVariants,
    List<RerankMatchedRoute> matchedRoutes,
    Map<String, Integer> routeRanks,
    Map<String, Double> routeScores,
    double fusedScore
) {
    public RerankRouteEvidence {
        queryVariants = queryVariants == null ? List.of() : List.copyOf(queryVariants);
        matchedRoutes = matchedRoutes == null ? List.of() : List.copyOf(matchedRoutes);
        var normalizedRouteRanks = routeRanks == null ? new LinkedHashMap<String, Integer>() : new LinkedHashMap<>(routeRanks);
        var normalizedRouteScores = routeScores == null ? new LinkedHashMap<String, Double>() : new LinkedHashMap<>(routeScores);
        for (var matchedRoute : matchedRoutes) {
            ensureConsistentRank(normalizedRouteRanks, matchedRoute);
            ensureConsistentScore(normalizedRouteScores, matchedRoute);
        }
        routeRanks = Collections.unmodifiableMap(normalizedRouteRanks);
        routeScores = Collections.unmodifiableMap(normalizedRouteScores);
    }

    public static RerankRouteEvidence empty() {
        return new RerankRouteEvidence(List.of(), List.of(), Map.of(), Map.of(), 0.0d);
    }

    private static void ensureConsistentRank(Map<String, Integer> routeRanks, RerankMatchedRoute matchedRoute) {
        var routeName = matchedRoute.routeName();
        var existingRank = routeRanks.get(routeName);
        if (existingRank != null && existingRank != matchedRoute.rank()) {
            throw new IllegalArgumentException("route rank must match matched route rank for " + routeName);
        }
        routeRanks.putIfAbsent(routeName, matchedRoute.rank());
    }

    private static void ensureConsistentScore(Map<String, Double> routeScores, RerankMatchedRoute matchedRoute) {
        var routeName = matchedRoute.routeName();
        var existingScore = routeScores.get(routeName);
        if (existingScore != null && Double.compare(existingScore, matchedRoute.score()) != 0) {
            throw new IllegalArgumentException("route score must match matched route score for " + routeName);
        }
        routeScores.putIfAbsent(routeName, matchedRoute.score());
    }
}
