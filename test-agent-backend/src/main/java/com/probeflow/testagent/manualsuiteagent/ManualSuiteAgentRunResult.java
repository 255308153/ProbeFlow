package com.probeflow.testagent.manualsuiteagent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ManualSuiteAgentRunResult(
    String schemaVersion,
    String runId,
    String fixtureId,
    String fixtureVersion,
    ManualSuiteAgentProviderMode providerMode,
    ManualSuiteAgentRunStatus status,
    Instant startedAt,
    Instant completedAt,
    String runProfile,
    boolean usesRealProvider,
    boolean usesExternalHttp,
    ManualSuiteAgentFixtureSummary fixtureSummary,
    List<ManualSuiteAgentSectionSummary> sections,
    List<ManualSuiteAgentDiagnostic> diagnostics,
    List<ManualSuiteAgentArtifactReference> artifacts,
    Map<String, Object> metadata
) {

    public static final String SCHEMA_VERSION = "v3-manual-suite-agent-harness.v1";
    public static final String USES_REAL_LLM_REPORT_KEY = "usesReal" + "Llm";

    public ManualSuiteAgentRunResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        sections = sections == null ? List.of() : List.copyOf(sections);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }

    ManualSuiteAgentRunResult withArtifacts(List<ManualSuiteAgentArtifactReference> newArtifacts) {
        return new ManualSuiteAgentRunResult(
            schemaVersion,
            runId,
            fixtureId,
            fixtureVersion,
            providerMode,
            status,
            startedAt,
            completedAt,
            runProfile,
            usesRealProvider,
            usesExternalHttp,
            fixtureSummary,
            sections,
            diagnostics,
            newArtifacts,
            metadata
        );
    }

    ManualSuiteAgentRunResult withDiagnostics(
        ManualSuiteAgentRunStatus newStatus,
        List<ManualSuiteAgentDiagnostic> newDiagnostics
    ) {
        return new ManualSuiteAgentRunResult(
            schemaVersion,
            runId,
            fixtureId,
            fixtureVersion,
            providerMode,
            newStatus,
            startedAt,
            completedAt,
            runProfile,
            usesRealProvider,
            usesExternalHttp,
            fixtureSummary,
            sections,
            newDiagnostics,
            artifacts,
            metadata
        );
    }
}
