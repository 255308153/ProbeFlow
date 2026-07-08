package com.probeflow.testagent.demorun;

import java.time.Instant;
import java.util.List;

public record DemoRunResult(
    String schemaVersion,
    String runId,
    String fixtureId,
    String fixtureVersion,
    DemoRunProviderMode providerMode,
    DemoRunStatus status,
    Instant startedAt,
    Instant completedAt,
    String runProfile,
    boolean usesRealLlm,
    boolean usesExternalHttp,
    DemoRunProviderSummary provider,
    DemoRunSectionView plan,
    DemoRunSectionView context,
    DemoRunSectionView tools,
    DemoRunSectionView suite,
    DemoRunSectionView execution,
    DemoRunSectionView variableAudit,
    DemoRunSectionView failureAnalysis,
    DemoRunSectionView memoryFeedback,
    DemoRunSectionView evaluation,
    DemoRunSectionView comparison,
    DemoRunSectionView errors,
    List<DemoRunArtifactReference> artifacts,
    List<DemoRunDiagnosticView> diagnostics
) {

    public static final String SCHEMA_VERSION = "v4-demo-run-result.v1";

    public DemoRunResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        comparison = comparison == null ? DemoRunSectionView.notRun("comparison", "Comparison") : comparison;
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
