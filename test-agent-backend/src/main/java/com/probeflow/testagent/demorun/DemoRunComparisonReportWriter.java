package com.probeflow.testagent.demorun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class DemoRunComparisonReportWriter {

    private final ObjectMapper objectMapper;

    DemoRunComparisonReportWriter() {
        this(new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    DemoRunComparisonReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<DemoRunArtifactReference> artifactReferences(
        String runId,
        Path outputDirectory,
        List<String> outputFormats,
        String fakeRunId,
        String realRunId
    ) {
        var runDirectory = outputDirectory.resolve(runId);
        var artifacts = new java.util.ArrayList<DemoRunArtifactReference>();
        if (outputFormats.contains("JSON_REPORT")) {
            artifacts.add(new DemoRunArtifactReference(
                "COMPARISON_JSON_REPORT",
                runDirectory.resolve("fake-vs-real-comparison.json").toAbsolutePath().toString(),
                "application/json",
                Map.of(
                    "schemaVersion", DemoRunResult.SCHEMA_VERSION,
                    "fakeRunId", fakeRunId,
                    "realRunId", realRunId
                )
            ));
        }
        if (outputFormats.contains("MARKDOWN_REPORT")) {
            artifacts.add(new DemoRunArtifactReference(
                "COMPARISON_MARKDOWN_REPORT",
                runDirectory.resolve("fake-vs-real-comparison.md").toAbsolutePath().toString(),
                "text/markdown",
                Map.of(
                    "schemaVersion", DemoRunResult.SCHEMA_VERSION,
                    "fakeRunId", fakeRunId,
                    "realRunId", realRunId
                )
            ));
        }
        return List.copyOf(artifacts);
    }

    void write(DemoRunResult comparisonResult, DemoRunResult fakeBaseline, DemoRunResult realRun) {
        var runDirectory = comparisonResult.artifacts().stream()
            .filter(artifact -> artifact.artifactType().startsWith("COMPARISON_"))
            .findFirst()
            .map(artifact -> Path.of(artifact.path()).getParent())
            .orElse(comparisonResult.provider().outputFormats().isEmpty()
                ? Path.of("target", "v4-demo-run", comparisonResult.runId())
                : Path.of("target", "v4-demo-run", comparisonResult.runId()));
        try {
            Files.createDirectories(runDirectory);
            for (var artifact : comparisonResult.artifacts()) {
                if ("COMPARISON_JSON_REPORT".equals(artifact.artifactType())) {
                    objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(Path.of(artifact.path()).toFile(), jsonReport(comparisonResult, fakeBaseline, realRun));
                }
                if ("COMPARISON_MARKDOWN_REPORT".equals(artifact.artifactType())) {
                    Files.writeString(Path.of(artifact.path()), markdownReport(comparisonResult, fakeBaseline, realRun));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write V4 comparison report artifacts", exception);
        }
    }

    private Map<String, Object> jsonReport(
        DemoRunResult comparisonResult,
        DemoRunResult fakeBaseline,
        DemoRunResult realRun
    ) {
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", comparisonResult.schemaVersion());
        report.put("runId", comparisonResult.runId());
        report.put("fixtureId", comparisonResult.fixtureId());
        report.put("provider", comparisonResult.provider());
        report.put("comparison", comparisonResult.comparison().summary());
        report.put("fakeBaseline", runSummary(fakeBaseline));
        report.put("realRun", runSummary(realRun));
        report.put("artifacts", comparisonResult.artifacts());
        report.put("diagnostics", comparisonResult.diagnostics());
        return report;
    }

    private Map<String, Object> runSummary(DemoRunResult result) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("runId", result.runId());
        summary.put("schemaVersion", result.schemaVersion());
        summary.put("fixtureId", result.fixtureId());
        summary.put("providerMode", result.providerMode());
        summary.put("status", result.status());
        summary.put("usesRealLlm", result.usesRealLlm());
        summary.put("usesExternalHttp", result.usesExternalHttp());
        summary.put("runProfile", result.runProfile());
        summary.put("plan", sectionSummary(result.plan()));
        summary.put("tools", sectionSummary(result.tools()));
        summary.put("failureAnalysis", sectionSummary(result.failureAnalysis()));
        summary.put("memoryFeedback", sectionSummary(result.memoryFeedback()));
        summary.put("evaluation", sectionSummary(result.evaluation()));
        summary.put("diagnostics", result.diagnostics());
        return summary;
    }

    private Map<String, Object> sectionSummary(DemoRunSectionView section) {
        return Map.of(
            "sectionId", section.sectionId(),
            "status", section.status(),
            "source", section.source(),
            "summary", section.summary()
        );
    }

    private String markdownReport(
        DemoRunResult comparisonResult,
        DemoRunResult fakeBaseline,
        DemoRunResult realRun
    ) {
        var markdown = new StringBuilder();
        markdown.append("# ProbeFlow V4 Fake vs Real LLM Comparison\n\n");
        markdown.append("## Run\n\n");
        markdown.append("- Run id: ").append(comparisonResult.runId()).append("\n");
        markdown.append("- Schema version: ").append(comparisonResult.schemaVersion()).append("\n");
        markdown.append("- Fixture: ").append(comparisonResult.fixtureId()).append("\n");
        markdown.append("- Provider mode: ").append(comparisonResult.providerMode()).append("\n");
        markdown.append("- Memory write: disabled for comparison\n\n");

        appendRun(markdown, "Fake baseline", fakeBaseline);
        appendRun(markdown, "Real run", realRun);

        markdown.append("## Differences\n\n");
        appendDifference(markdown, "Plan steps", comparisonResult, "planStepDifferences");
        appendDifference(markdown, "Tool selection", comparisonResult, "toolSelectionDifferences");
        appendDifference(markdown, "Failure analysis", comparisonResult, "failureAnalysisDifferences");
        appendDifference(markdown, "Memory feedback", comparisonResult, "memoryFeedbackDifferences");
        appendDifference(markdown, "Report summary", comparisonResult, "reportSummaryDifferences");

        markdown.append("\n## Human Judgment Notes\n\n");
        markdown.append("- Comparison shows behavior differences; it does not assume the real LLM is better.\n");
        markdown.append("- Fake baseline remains the deterministic reference.\n");
        markdown.append("- Real run failures are displayed as evidence instead of crashing the demo.\n\n");

        markdown.append("## Artifact References\n\n");
        for (var artifact : comparisonResult.artifacts()) {
            markdown.append("- ").append(artifact.artifactType()).append(": ").append(artifact.path()).append("\n");
        }
        return markdown.toString();
    }

    private void appendRun(StringBuilder markdown, String title, DemoRunResult result) {
        markdown.append("## ").append(title).append("\n\n");
        markdown.append("- Run id: ").append(result.runId()).append("\n");
        markdown.append("- Provider: ").append(result.providerMode()).append("\n");
        markdown.append("- Status: ").append(result.status()).append("\n");
        markdown.append("- Uses real LLM: ").append(result.usesRealLlm()).append("\n");
        markdown.append("- Uses external HTTP: ").append(result.usesExternalHttp()).append("\n");
        if (!result.diagnostics().isEmpty()) {
            markdown.append("- Diagnostics: ");
            markdown.append(result.diagnostics().stream().map(DemoRunDiagnosticView::code).toList());
            markdown.append("\n");
        }
        markdown.append("\n");
    }

    private void appendDifference(StringBuilder markdown, String title, DemoRunResult result, String key) {
        var difference = result.comparison().summary().get(key);
        markdown.append("- ").append(title).append(": ").append(difference).append("\n");
    }
}
