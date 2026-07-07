package com.probeflow.testagent.manualsuiteagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualSuiteAgentHarnessIssue05Tests {

    @TempDir
    private Path outputDir;

    @Test
    void localCliRunsDefaultFixtureAndPrintsArtifactPathsWithoutExternalDependencies() {
        var stdout = new ByteArrayOutputStream();

        var result = ManualSuiteAgentHarnessCli.run(new String[] {
            "--output-dir=" + outputDir
        }, new PrintStream(stdout, true, StandardCharsets.UTF_8));

        assertThat(result.status()).isEqualTo(ManualSuiteAgentRunStatus.COMPLETED);
        assertThat(result.fixtureId()).isEqualTo("order-suite-demo");
        assertThat(result.providerMode()).isEqualTo(ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE);
        assertThat(result.usesRealProvider()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();

        var output = stdout.toString(StandardCharsets.UTF_8);
        assertThat(output)
            .contains("status=COMPLETED")
            .contains("fixtureId=order-suite-demo")
            .contains("providerMode=DETERMINISTIC_FAKE")
            .contains("usesRealLlm=false")
            .contains("usesExternalHttp=false")
            .contains("jsonReport=")
            .contains("markdownReport=");
        assertThat(Files.exists(artifactPath(result, "JSON_REPORT"))).isTrue();
        assertThat(Files.exists(artifactPath(result, "MARKDOWN_REPORT"))).isTrue();
        assertThat(artifactPath(result, "JSON_REPORT")).startsWith(outputDir.toAbsolutePath());
    }

    @Test
    void localCliAcceptsFixtureAndOutputDirectoryAndCanRunTwiceWithoutConflicts() {
        var firstOut = new ByteArrayOutputStream();
        var secondOut = new ByteArrayOutputStream();

        var first = ManualSuiteAgentHarnessCli.run(new String[] {
            "--fixture-id=v3-smoke",
            "--output-dir=" + outputDir.resolve("demo-output")
        }, new PrintStream(firstOut, true, StandardCharsets.UTF_8));
        var second = ManualSuiteAgentHarnessCli.run(new String[] {
            "--fixture-id=v3-smoke",
            "--output-dir=" + outputDir.resolve("demo-output")
        }, new PrintStream(secondOut, true, StandardCharsets.UTF_8));

        assertThat(first.status()).isEqualTo(ManualSuiteAgentRunStatus.COMPLETED);
        assertThat(second.status()).isEqualTo(ManualSuiteAgentRunStatus.COMPLETED);
        assertThat(second.runId()).isNotEqualTo(first.runId());
        assertThat(artifactPath(first, "JSON_REPORT")).isNotEqualTo(artifactPath(second, "JSON_REPORT"));
        assertThat(firstOut.toString(StandardCharsets.UTF_8)).contains("fixtureId=v3-smoke");
        assertThat(secondOut.toString(StandardCharsets.UTF_8)).contains("fixtureId=v3-smoke");
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
