package com.probeflow.testagent.manualsuiteagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualSuiteAgentHarnessIssue01Tests {

    @TempDir
    private Path outputDir;

    @Test
    void smokeFixtureRunsWithFakeProviderAndWritesStableJsonAndMarkdownArtifacts() throws Exception {
        var harness = ManualSuiteAgentHarness.defaults();
        var request = ManualSuiteAgentRunRequest.fake("v3-smoke", outputDir);

        var first = harness.run(request);
        var second = harness.run(request);

        assertThat(first.status()).isEqualTo(ManualSuiteAgentRunStatus.COMPLETED);
        assertThat(first.fixtureId()).isEqualTo("v3-smoke");
        assertThat(first.fixtureVersion()).isEqualTo("2026.07.v1");
        assertThat(first.providerMode()).isEqualTo(ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE);
        assertThat(first.usesRealProvider()).isFalse();
        assertThat(first.usesExternalHttp()).isFalse();
        assertThat(first.sections())
            .extracting(ManualSuiteAgentSectionSummary::sectionId)
            .containsExactly("input", "run-summary", "sections", "diagnostics");
        assertThat(first.diagnostics()).isEmpty();
        assertThat(first.artifacts())
            .extracting(ManualSuiteAgentArtifactReference::artifactType)
            .containsExactly("JSON_REPORT", "MARKDOWN_REPORT");

        assertThat(second.status()).isEqualTo(ManualSuiteAgentRunStatus.COMPLETED);
        assertThat(second.runId()).isNotEqualTo(first.runId());

        var jsonPath = artifactPath(first, "JSON_REPORT");
        var markdownPath = artifactPath(first, "MARKDOWN_REPORT");
        assertThat(Files.exists(jsonPath)).isTrue();
        assertThat(Files.exists(markdownPath)).isTrue();

        var json = new ObjectMapper().readTree(jsonPath.toFile());
        assertThat(json.get("schemaVersion").asText()).isEqualTo("v3-manual-suite-agent-harness.v1");
        assertThat(json.at("/run/runId").asText()).isEqualTo(first.runId());
        assertThat(json.at("/run/fixtureId").asText()).isEqualTo("v3-smoke");
        assertThat(json.at("/run/fixtureVersion").asText()).isEqualTo("2026.07.v1");
        assertThat(json.at("/run/providerMode").asText()).isEqualTo("DETERMINISTIC_FAKE");
        assertThat(json.at("/run/status").asText()).isEqualTo("COMPLETED");
        assertThat(json.at("/run/usesRealLlm").asBoolean()).isFalse();
        assertThat(json.at("/run/usesExternalHttp").asBoolean()).isFalse();
        assertThat(json.get("fixture").get("fixtureId").asText()).isEqualTo("v3-smoke");
        assertThat(json.get("sections")).hasSize(4);
        assertThat(json.get("diagnostics")).isEmpty();
        assertThat(json.get("artifacts")).hasSize(2);

        var markdown = Files.readString(markdownPath);
        assertThat(markdown)
            .contains("# Manual Suite Agent Harness Report")
            .contains("## Input")
            .contains("## Run Summary")
            .contains("## Sections")
            .contains("## Diagnostics")
            .contains("## Artifact References")
            .contains(first.runId())
            .contains("v3-smoke")
            .contains("2026.07.v1")
            .contains("DETERMINISTIC_FAKE")
            .contains("COMPLETED");
    }

    private Path artifactPath(ManualSuiteAgentRunResult result, String artifactType) {
        return result.artifacts().stream()
            .filter(artifact -> artifact.artifactType().equals(artifactType))
            .findFirst()
            .map(ManualSuiteAgentArtifactReference::path)
            .map(Path::of)
            .orElseThrow();
    }
}
