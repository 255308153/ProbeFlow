package com.probeflow.testagent.retrieval;

public record RoutingBudgetPolicy(
    int totalCandidateLimit,
    int tokenBudget,
    boolean enforceStatusGate,
    boolean enforceConfidenceGate,
    boolean enforcePermissionGate
) {
    public RoutingBudgetPolicy {
        totalCandidateLimit = Math.max(1, totalCandidateLimit);
        tokenBudget = Math.max(1, tokenBudget);
    }

    public static RoutingBudgetPolicy conservativeDefault() {
        return new RoutingBudgetPolicy(24, 1200, true, true, true);
    }
}
