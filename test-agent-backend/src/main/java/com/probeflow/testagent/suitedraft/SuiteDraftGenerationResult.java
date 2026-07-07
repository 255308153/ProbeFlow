package com.probeflow.testagent.suitedraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SuiteDraftGenerationResult(
    String schemaVersion,
    SuiteDraftGenerationStatus status,
    SuiteReadinessStatus readinessStatus,
    String fixtureId,
    SuiteDraftProviderMode providerMode,
    boolean usesManualProvider,
    boolean usesExternalHttp,
    SuiteDraft draft,
    List<SuiteVariableDependency> dependencyLinks,
    List<SuiteReadinessDiagnostic> diagnostics,
    List<SuiteReadinessDiagnostic> blockers,
    Map<String, Object> metadata
) {

    public static final String SCHEMA_VERSION = "v3-suite-draft-generation.v1";

    public SuiteDraftGenerationResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        providerMode = providerMode == null ? SuiteDraftProviderMode.DETERMINISTIC_FAKE : providerMode;
        dependencyLinks = dependencyLinks == null ? List.of() : List.copyOf(dependencyLinks);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
