package com.probeflow.testagent.suitedraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SuiteReadinessDiagnostic(
    String code,
    String severity,
    List<String> affectedSteps,
    String affectedDependencyId,
    String reason,
    String recommendedAction,
    Map<String, Object> metadata
) {

    public SuiteReadinessDiagnostic {
        severity = severity == null || severity.isBlank() ? "WARN" : severity;
        affectedSteps = affectedSteps == null ? List.of() : List.copyOf(affectedSteps);
        reason = reason == null ? "" : reason;
        recommendedAction = recommendedAction == null ? "" : recommendedAction;
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }

    public static SuiteReadinessDiagnostic error(
        String code,
        List<String> affectedSteps,
        String affectedDependencyId,
        String reason,
        String recommendedAction,
        Map<String, Object> metadata
    ) {
        return new SuiteReadinessDiagnostic(
            code,
            "ERROR",
            affectedSteps,
            affectedDependencyId,
            reason,
            recommendedAction,
            metadata
        );
    }

    public static SuiteReadinessDiagnostic warn(
        String code,
        List<String> affectedSteps,
        String affectedDependencyId,
        String reason,
        String recommendedAction,
        Map<String, Object> metadata
    ) {
        return new SuiteReadinessDiagnostic(
            code,
            "WARN",
            affectedSteps,
            affectedDependencyId,
            reason,
            recommendedAction,
            metadata
        );
    }
}
