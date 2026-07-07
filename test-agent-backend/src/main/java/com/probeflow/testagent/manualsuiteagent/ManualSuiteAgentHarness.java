package com.probeflow.testagent.manualsuiteagent;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ManualSuiteAgentHarness {

    private final ManualSuiteAgentFixtureRegistry fixtureRegistry;
    private final ManualSuiteAgentReportWriter reportWriter;

    public ManualSuiteAgentHarness(
        ManualSuiteAgentFixtureRegistry fixtureRegistry,
        ManualSuiteAgentReportWriter reportWriter
    ) {
        this.fixtureRegistry = fixtureRegistry;
        this.reportWriter = reportWriter;
    }

    public static ManualSuiteAgentHarness defaults() {
        return new ManualSuiteAgentHarness(
            ManualSuiteAgentFixtureRegistry.defaults(),
            new ManualSuiteAgentReportWriter()
        );
    }

    public ManualSuiteAgentRunResult run(ManualSuiteAgentRunRequest request) {
        var startedAt = Instant.now();
        var fixture = fixtureRegistry.findById(request.fixtureId());
        if (fixture.isEmpty()) {
            var completedAt = Instant.now();
            return writeBestEffort(baseResult(
                request,
                null,
                ManualSuiteAgentRunStatus.FAILED,
                startedAt,
                completedAt,
                List.of(),
                List.of(ManualSuiteAgentDiagnostic.error(
                    "FIXTURE_NOT_FOUND",
                    "Fixture not found: " + request.fixtureId(),
                    Map.of("fixtureId", request.fixtureId())
                )),
                Map.of()
            ), request);
        }

        var sections = baseSections(request, fixture.get());
        var completedAt = Instant.now();
        var result = baseResult(
            request,
            fixture.get(),
            ManualSuiteAgentRunStatus.COMPLETED,
            startedAt,
            completedAt,
            sections,
            List.of(),
            Map.of("harnessScope", "v3-1-manual-suite-agent-harness")
        );
        return writeBestEffort(result, request);
    }

    private ManualSuiteAgentRunResult writeBestEffort(
        ManualSuiteAgentRunResult result,
        ManualSuiteAgentRunRequest request
    ) {
        try {
            return reportWriter.write(result, request.outputDirectory());
        } catch (IOException exception) {
            var diagnostics = new java.util.ArrayList<>(result.diagnostics());
            diagnostics.add(ManualSuiteAgentDiagnostic.error(
                "REPORT_WRITE_FAILED",
                "Report write failed: " + exception.getMessage(),
                Map.of("outputDirectory", request.outputDirectory().toAbsolutePath().toString())
            ));
            return result.withDiagnostics(ManualSuiteAgentRunStatus.FAILED, diagnostics);
        }
    }

    private ManualSuiteAgentRunResult baseResult(
        ManualSuiteAgentRunRequest request,
        ManualSuiteAgentFixture fixture,
        ManualSuiteAgentRunStatus status,
        Instant startedAt,
        Instant completedAt,
        List<ManualSuiteAgentSectionSummary> sections,
        List<ManualSuiteAgentDiagnostic> diagnostics,
        Map<String, Object> metadata
    ) {
        var fixtureSummary = fixture == null
            ? missingFixtureSummary(request.fixtureId())
            : fixture.summary();
        return new ManualSuiteAgentRunResult(
            ManualSuiteAgentRunResult.SCHEMA_VERSION,
            "v3h-" + UUID.randomUUID(),
            fixtureSummary.fixtureId(),
            fixtureSummary.fixtureVersion(),
            request.providerMode(),
            status,
            startedAt,
            completedAt,
            request.runProfile(),
            request.providerMode() == ManualSuiteAgentProviderMode.MANUAL_REAL_LLM && request.allowManualRealLlm(),
            request.allowExternalHttp(),
            fixtureSummary,
            sections,
            diagnostics,
            List.of(),
            metadata
        );
    }

    private ManualSuiteAgentFixtureSummary missingFixtureSummary(String fixtureId) {
        return new ManualSuiteAgentFixtureSummary(
            fixtureId,
            "unknown",
            "Unknown fixture",
            "Fixture metadata could not be loaded.",
            List.of(),
            Map.of()
        );
    }

    private List<ManualSuiteAgentSectionSummary> baseSections(
        ManualSuiteAgentRunRequest request,
        ManualSuiteAgentFixture fixture
    ) {
        return List.of(
            new ManualSuiteAgentSectionSummary(
                "input",
                "Fixture and provider input",
                ManualSuiteAgentSectionSource.FIXTURE,
                "READY",
                orderedMap(
                    "fixtureId", fixture.fixtureId(),
                    "fixtureVersion", fixture.fixtureVersion(),
                    "providerMode", request.providerMode().name()
                )
            ),
            new ManualSuiteAgentSectionSummary(
                "run-summary",
                "Harness run summary",
                ManualSuiteAgentSectionSource.REAL,
                "READY",
                orderedMap(
                    "usesRealLlm", false,
                    "usesExternalHttp", false,
                    "runProfile", request.runProfile()
                )
            ),
            new ManualSuiteAgentSectionSummary(
                "sections",
                "V3 section registry placeholder",
                ManualSuiteAgentSectionSource.STAGED,
                "READY",
                orderedMap("sectionCount", 4)
            ),
            new ManualSuiteAgentSectionSummary(
                "diagnostics",
                "Structured diagnostics",
                ManualSuiteAgentSectionSource.REAL,
                "READY",
                orderedMap("diagnosticCount", 0)
            )
        );
    }

    private Map<String, Object> orderedMap(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i].toString(), keyValues[i + 1]);
        }
        return map;
    }
}
