package com.probeflow.testagent.manualsuiteagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualSuiteAgentHarnessIssue04Tests {

    @TempDir
    private Path outputDir;

    @Test
    void defaultRunUsesFakeProviderAndBlocksManualRealLlmUnlessExplicitlyAllowed() throws Exception {
        var harness = ManualSuiteAgentHarness.defaults();

        var defaultRun = harness.run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));
        assertThat(defaultRun.providerMode()).isEqualTo(ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE);
        assertThat(defaultRun.usesRealLlm()).isFalse();
        assertThat(defaultRun.usesExternalHttp()).isFalse();

        var blocked = harness.run(ManualSuiteAgentRunRequest.manualRealLlm("order-suite-demo", outputDir, false));
        assertThat(blocked.status()).isEqualTo(ManualSuiteAgentRunStatus.BLOCKED);
        assertThat(blocked.usesRealLlm()).isFalse();
        assertThat(blocked.diagnostics())
            .extracting(ManualSuiteAgentDiagnostic::code)
            .containsExactly("PROVIDER_BLOCKED");

        var json = new ObjectMapper().readTree(Files.readString(artifactPath(blocked, "JSON_REPORT")));
        assertThat(json.at("/run/usesRealLlm").asBoolean()).isFalse();
        assertThat(json.at("/run/providerMode").asText()).isEqualTo("MANUAL_REAL_LLM");
        assertThat(json.get("diagnostics").get(0).get("code").asText()).isEqualTo("PROVIDER_BLOCKED");
    }

    @Test
    void invalidProviderUnknownFixtureInvalidFixtureAndReportWriteFailureReturnStructuredDiagnostics() throws Exception {
        var harness = ManualSuiteAgentHarness.defaults();

        var invalidProvider = harness.run(
            ManualSuiteAgentRunRequest.withProviderMode("order-suite-demo", "AUTO_GPT_LOOP", outputDir)
        );
        assertThat(invalidProvider.status()).isEqualTo(ManualSuiteAgentRunStatus.FAILED);
        assertThat(invalidProvider.diagnostics())
            .extracting(ManualSuiteAgentDiagnostic::code)
            .containsExactly("INVALID_PROVIDER_MODE");
        assertThat(invalidProvider.metadata()).doesNotContainKey("executionSummary");

        var missingFixture = harness.run(ManualSuiteAgentRunRequest.fake("missing-fixture", outputDir));
        assertThat(missingFixture.status()).isEqualTo(ManualSuiteAgentRunStatus.FAILED);
        assertThat(missingFixture.diagnostics())
            .extracting(ManualSuiteAgentDiagnostic::code)
            .containsExactly("FIXTURE_NOT_FOUND");

        var invalidFixture = harness.run(ManualSuiteAgentRunRequest.fake("v3-invalid-fixture", outputDir));
        assertThat(invalidFixture.status()).isEqualTo(ManualSuiteAgentRunStatus.FAILED);
        assertThat(invalidFixture.diagnostics())
            .extracting(ManualSuiteAgentDiagnostic::code)
            .containsExactly("FIXTURE_INVALID");
        var invalidFixtureJson = Files.readString(artifactPath(invalidFixture, "JSON_REPORT"));
        assertThat(invalidFixtureJson)
            .doesNotContain("invalid-fixture-token", "invalid-password", "secret-cookie", "api-key-123")
            .contains("[REDACTED]");

        var notDirectory = outputDir.resolve("not-a-directory");
        Files.writeString(notDirectory, "occupied");
        var writeFailure = harness.run(ManualSuiteAgentRunRequest.fake("v3-smoke", notDirectory));
        assertThat(writeFailure.status()).isEqualTo(ManualSuiteAgentRunStatus.FAILED);
        assertThat(writeFailure.artifacts()).isEmpty();
        assertThat(writeFailure.diagnostics())
            .extracting(ManualSuiteAgentDiagnostic::code)
            .containsExactly("REPORT_WRITE_FAILED");
    }

    @Test
    void reportsAndMarkdownRedactSensitiveFixtureEnvironmentAuthBodyAndArtifactMetadata() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        var jsonText = Files.readString(artifactPath(result, "JSON_REPORT"));
        var markdown = Files.readString(artifactPath(result, "MARKDOWN_REPORT"));

        assertThat(jsonText)
            .doesNotContain(
                "order-demo-token",
                "Bearer order-demo-token",
                "session-cookie-secret",
                "Authorization: Bearer",
                "api-key-123"
            )
            .contains("[REDACTED]");
        assertThat(markdown)
            .doesNotContain("order-demo-token", "Bearer order-demo-token", "session-cookie-secret");

        var json = new ObjectMapper().readTree(jsonText);
        assertThat(json.at("/run/usesExternalHttp").asBoolean()).isFalse();
        assertThat(json.at("/run/runProfile").asText()).isEqualTo("local-demo");
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
