package com.probeflow.testagent.manualsuiteagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ManualSuiteAgentReportWriter {

    private final ObjectMapper objectMapper;
    private final ManualSuiteAgentRedactor redactor = new ManualSuiteAgentRedactor();

    public ManualSuiteAgentReportWriter() {
        this(new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    ManualSuiteAgentReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ManualSuiteAgentRunResult write(ManualSuiteAgentRunResult result, Path outputDirectory) throws IOException {
        var runDirectory = outputDirectory.resolve(result.runId());
        Files.createDirectories(runDirectory);
        var jsonPath = runDirectory.resolve("manual-suite-agent-report.json");
        var markdownPath = runDirectory.resolve("manual-suite-agent-report.md");
        var artifacts = List.of(
            new ManualSuiteAgentArtifactReference(
                "JSON_REPORT",
                jsonPath.toAbsolutePath().toString(),
                "application/json",
                Map.of("schemaVersion", result.schemaVersion())
            ),
            new ManualSuiteAgentArtifactReference(
                "MARKDOWN_REPORT",
                markdownPath.toAbsolutePath().toString(),
                "text/markdown",
                Map.of("schemaVersion", result.schemaVersion())
            )
        );
        var resultWithArtifacts = result.withArtifacts(artifacts);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), toJsonReport(resultWithArtifacts));
        Files.writeString(markdownPath, toMarkdownReport(resultWithArtifacts));
        return resultWithArtifacts;
    }

    private Map<String, Object> toJsonReport(ManualSuiteAgentRunResult result) {
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", result.schemaVersion());
        report.put("run", runSummary(result));
        report.put("fixture", fixtureSummary(result));
        report.put("sections", result.sections().stream().map(this::sectionSummary).toList());
        report.put("diagnostics", result.diagnostics().stream().map(this::diagnosticSummary).toList());
        report.put("artifacts", result.artifacts().stream().map(this::artifactSummary).toList());
        report.put("metadata", redactor.redactMap(result.metadata()));
        return report;
    }

    private Map<String, Object> runSummary(ManualSuiteAgentRunResult result) {
        var run = new LinkedHashMap<String, Object>();
        run.put("runId", result.runId());
        run.put("fixtureId", result.fixtureId());
        run.put("fixtureVersion", result.fixtureVersion());
        run.put("providerMode", result.providerMode().name());
        run.put("status", result.status().name());
        run.put("startedAt", result.startedAt().toString());
        run.put("completedAt", result.completedAt().toString());
        run.put("durationMs", Duration.between(result.startedAt(), result.completedAt()).toMillis());
        run.put("runProfile", result.runProfile());
        run.put("usesRealLlm", result.usesRealLlm());
        run.put("usesExternalHttp", result.usesExternalHttp());
        return run;
    }

    private Map<String, Object> fixtureSummary(ManualSuiteAgentRunResult result) {
        var fixture = new LinkedHashMap<String, Object>();
        fixture.put("fixtureId", result.fixtureSummary().fixtureId());
        fixture.put("fixtureVersion", result.fixtureSummary().fixtureVersion());
        fixture.put("displayName", result.fixtureSummary().displayName());
        fixture.put("description", result.fixtureSummary().description());
        fixture.put("capabilityTags", result.fixtureSummary().capabilityTags());
        fixture.put("metadata", redactor.redactMap(result.fixtureSummary().metadata()));
        return fixture;
    }

    private Map<String, Object> sectionSummary(ManualSuiteAgentSectionSummary section) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("sectionId", section.sectionId());
        summary.put("title", section.title());
        summary.put("source", section.source().name());
        summary.put("status", section.status());
        summary.put("summary", redactor.redactMap(section.summary()));
        return summary;
    }

    private Map<String, Object> diagnosticSummary(ManualSuiteAgentDiagnostic diagnostic) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("code", diagnostic.code());
        summary.put("severity", diagnostic.severity());
        summary.put("message", diagnostic.message());
        summary.put("metadata", redactor.redactMap(diagnostic.metadata()));
        return summary;
    }

    private Map<String, Object> artifactSummary(ManualSuiteAgentArtifactReference artifact) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("artifactType", artifact.artifactType());
        summary.put("path", artifact.path());
        summary.put("mediaType", artifact.mediaType());
        summary.put("metadata", redactor.redactMap(artifact.metadata()));
        return summary;
    }

    private String toMarkdownReport(ManualSuiteAgentRunResult result) {
        var markdown = new StringBuilder();
        markdown.append("# Manual Suite Agent Harness Report\n\n");
        markdown.append("## Input\n\n");
        markdown.append("- Fixture: ").append(result.fixtureId()).append("\n");
        markdown.append("- Fixture version: ").append(result.fixtureVersion()).append("\n");
        markdown.append("- Provider mode: ").append(result.providerMode().name()).append("\n");
        markdown.append("- Run profile: ").append(result.runProfile()).append("\n\n");

        markdown.append("## Run Summary\n\n");
        markdown.append("- Run id: ").append(result.runId()).append("\n");
        markdown.append("- Status: ").append(result.status().name()).append("\n");
        markdown.append("- Uses real LLM: ").append(result.usesRealLlm()).append("\n");
        markdown.append("- Uses external HTTP: ").append(result.usesExternalHttp()).append("\n");
        markdown.append("- Started at: ").append(result.startedAt()).append("\n");
        markdown.append("- Completed at: ").append(result.completedAt()).append("\n\n");

        markdown.append("## Sections\n\n");
        for (var section : result.sections()) {
            markdown.append("- ")
                .append(section.sectionId())
                .append(" (")
                .append(section.source().name())
                .append(", ")
                .append(section.status())
                .append("): ")
                .append(section.title())
                .append("\n");
        }
        markdown.append("\n");

        markdown.append("## Diagnostics\n\n");
        if (result.diagnostics().isEmpty()) {
            markdown.append("- None\n\n");
        } else {
            for (var diagnostic : result.diagnostics()) {
                markdown.append("- ")
                    .append(diagnostic.severity())
                    .append(" ")
                    .append(diagnostic.code())
                    .append(": ")
                    .append(diagnostic.message())
                    .append("\n");
            }
            markdown.append("\n");
        }

        markdown.append("## Artifact References\n\n");
        for (var artifact : result.artifacts()) {
            markdown.append("- ")
                .append(artifact.artifactType())
                .append(": ")
                .append(artifact.path())
                .append("\n");
        }
        return markdown.toString();
    }
}
