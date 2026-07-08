package com.probeflow.testagent.demorun;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoRunApplicationServiceIssue05Tests {

    @TempDir
    Path outputDirectory;

    @Test
    void comparisonModeProducesFakeBaselineRealRunDifferencesAndArtifacts() throws Exception {
        var service = new DemoRunApplicationService(
            ManualSuiteAgentHarness.defaults(),
            (request, input) -> new DemoRunRealLlmProbeResult(
                DemoRunRealLlmProbeStatus.SUCCESS,
                "ok",
                List.of(new DemoRunLlmCallSummary(
                    "V4_DEMO_REAL_LLM_PREFLIGHT",
                    "SUCCESS",
                    "manual-real",
                    "real-model",
                    8,
                    4,
                    12,
                    "NONE",
                    null,
                    Map.of("traceId", "trace-success")
                )),
                Map.of()
            )
        );

        var result = service.run(comparisonRequest());

        assertThat(result.status()).isEqualTo(DemoRunStatus.COMPLETED);
        assertThat(result.providerMode()).isEqualTo(DemoRunProviderMode.COMPARISON);
        assertThat(result.provider().comparisonEnabled()).isTrue();
        assertThat(result.provider().allowMemoryWrite()).isFalse();
        assertThat(result.comparison().sectionId()).isEqualTo("comparison");
        assertThat(result.comparison().summary())
            .containsKeys(
                "fakeBaseline",
                "realRun",
                "planStepDifferences",
                "toolSelectionDifferences",
                "failureAnalysisDifferences",
                "memoryFeedbackDifferences",
                "reportSummaryDifferences"
            )
            .containsEntry("memoryWriteSuppressed", true);
        assertThat(result.comparison().summary().get("fakeBaseline").toString())
            .contains("providerMode=FAKE");
        assertThat(result.comparison().summary().get("realRun").toString())
            .contains("providerMode=REAL", "usesRealLlm=true");
        assertThat(result.provider().llmCalls()).singleElement()
            .extracting(DemoRunLlmCallSummary::status)
            .isEqualTo("SUCCESS");
        assertComparisonArtifacts(result);
    }

    @Test
    void comparisonModeKeepsFakeBaselineWhenRealRunIsUnavailable() throws Exception {
        var service = new DemoRunApplicationService();

        var result = service.run(comparisonRequest());

        assertThat(result.status()).isEqualTo(DemoRunStatus.COMPLETED);
        assertThat(result.providerMode()).isEqualTo(DemoRunProviderMode.COMPARISON);
        assertThat(result.comparison().summary().get("fakeBaseline").toString())
            .contains("status=COMPLETED", "providerMode=FAKE");
        assertThat(result.comparison().summary().get("realRun").toString())
            .contains("status=REJECTED", "providerMode=REAL", "REAL_LLM_NOT_AVAILABLE");
        assertThat(result.errors().summary().toString()).contains("REAL_LLM_NOT_AVAILABLE");
        assertThat(result.provider().allowMemoryWrite()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertComparisonArtifacts(result);
    }

    private DemoRunRequest comparisonRequest() {
        return new DemoRunRequest(
            "order-suite-demo",
            DemoRunProviderMode.COMPARISON,
            "comparison-demo",
            outputDirectory,
            true,
            true,
            List.of("JSON_REPORT", "MARKDOWN_REPORT")
        );
    }

    private void assertComparisonArtifacts(DemoRunResult result) throws Exception {
        assertThat(result.artifacts())
            .extracting(DemoRunArtifactReference::artifactType)
            .contains("COMPARISON_JSON_REPORT", "COMPARISON_MARKDOWN_REPORT");

        var jsonPath = artifactPath(result, "COMPARISON_JSON_REPORT");
        var markdownPath = artifactPath(result, "COMPARISON_MARKDOWN_REPORT");
        assertThat(jsonPath).exists();
        assertThat(markdownPath).exists();

        var json = new ObjectMapper().readTree(jsonPath.toFile());
        assertThat(json.at("/runId").asText()).isEqualTo(result.runId());
        assertThat(json.at("/schemaVersion").asText()).isEqualTo("v4-demo-run-result.v1");
        assertThat(json.at("/fixtureId").asText()).isEqualTo("order-suite-demo");
        assertThat(json.at("/provider/providerMode").asText()).isEqualTo("COMPARISON");
        assertThat(json.at("/artifacts").isArray()).isTrue();

        var markdown = Files.readString(markdownPath);
        assertThat(markdown)
            .contains("Fake baseline", "Real run", "Plan steps", "Tool selection")
            .doesNotContain("sk-test-secret")
            .doesNotContain("unredacted-fixture-secret");
    }

    private Path artifactPath(DemoRunResult result, String artifactType) {
        return result.artifacts().stream()
            .filter(artifact -> artifact.artifactType().equals(artifactType))
            .findFirst()
            .map(artifact -> Path.of(artifact.path()))
            .orElseThrow();
    }
}
