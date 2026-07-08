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

class DemoRunApplicationServiceIssue06Tests {

    @TempDir
    Path outputDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void realLlmPolicyRejectionRedactsSensitiveOutputAcrossDemoResult() throws Exception {
        var service = new DemoRunApplicationService(
            ManualSuiteAgentHarness.defaults(),
            (request, input) -> new DemoRunRealLlmProbeResult(
                DemoRunRealLlmProbeStatus.BLOCKED,
                "Policy blocked Authorization: Bearer real-secret-token",
                List.of(new DemoRunLlmCallSummary(
                    "V4_DEMO_REAL_LLM_PREFLIGHT",
                    "FAILED",
                    "manual-real",
                    "real-model",
                    7,
                    5,
                    12,
                    "POLICY_BLOCKED",
                    "model key sk-test-secret was blocked",
                    Map.of(
                        "apiKey", "sk-test-secret",
                        "Authorization", "Bearer real-secret-token",
                        "safeTraceId", "trace-redaction"
                    )
                )),
                Map.of(
                    "modelKey", "sk-test-secret",
                    "headers", Map.of("Authorization", "Bearer real-secret-token"),
                    "policy", "manual real provider disabled"
                )
            )
        );

        var result = service.run(realRequest(false));
        var json = objectMapper.writeValueAsString(result);

        assertThat(result.status()).isEqualTo(DemoRunStatus.REJECTED);
        assertThat(result.diagnostics())
            .extracting(DemoRunDiagnosticView::code)
            .contains("REAL_LLM_POLICY_BLOCKED");
        assertThat(result.errors().summary().toString()).contains("manual real provider disabled");
        assertThat(json)
            .contains(DemoRunRedactor.REDACTED)
            .contains("REAL_LLM_POLICY_BLOCKED")
            .doesNotContain("real-secret-token")
            .doesNotContain("sk-test-secret")
            .doesNotContain("Bearer real-secret-token");
    }

    @Test
    void comparisonModeSuppressesMemoryWriteAndRedactsComparisonArtifacts() throws Exception {
        var service = new DemoRunApplicationService(
            ManualSuiteAgentHarness.defaults(),
            (request, input) -> new DemoRunRealLlmProbeResult(
                DemoRunRealLlmProbeStatus.FAILED,
                "real run failed with api_key=sk-test-secret",
                List.of(new DemoRunLlmCallSummary(
                    "V4_DEMO_REAL_LLM_PREFLIGHT",
                    "FAILED",
                    "manual-real",
                    "real-model",
                    4,
                    2,
                    6,
                    "TIMEOUT",
                    "timeout for Authorization: Bearer real-secret-token",
                    Map.of("credential", "Bearer real-secret-token")
                )),
                Map.of("apiKey", "sk-test-secret")
            )
        );

        var result = service.run(new DemoRunRequest(
            "order-suite-demo",
            DemoRunProviderMode.COMPARISON,
            "comparison-demo",
            outputDirectory,
            true,
            true,
            List.of("JSON_REPORT", "MARKDOWN_REPORT")
        ));

        assertThat(result.status()).isEqualTo(DemoRunStatus.COMPLETED);
        assertThat(result.provider().allowMemoryWrite()).isFalse();
        assertThat(result.comparison().summary()).containsEntry("memoryWriteSuppressed", true);
        assertThat(result.memoryFeedback().summary().get("memoryWrite").toString())
            .contains("requested=false", "enabled=false", "actualWritePerformed=false");

        var resultJson = objectMapper.writeValueAsString(result);
        assertThat(resultJson)
            .contains(DemoRunRedactor.REDACTED)
            .doesNotContain("real-secret-token")
            .doesNotContain("sk-test-secret");

        var markdown = Files.readString(artifactPath(result, "COMPARISON_MARKDOWN_REPORT"));
        var json = Files.readString(artifactPath(result, "COMPARISON_JSON_REPORT"));
        assertThat(markdown).doesNotContain("real-secret-token", "sk-test-secret", "api_key=");
        assertThat(json).doesNotContain("real-secret-token", "sk-test-secret", "api_key=");
    }

    @Test
    void realLlmMemoryWriteIsDisabledByDefaultAndRequiresExplicitManualProfileSwitch() {
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
                    3,
                    2,
                    5,
                    "NONE",
                    null,
                    Map.of("traceId", "trace-memory")
                )),
                Map.of()
            )
        );

        var defaultResult = service.run(realRequest(false));
        var explicitResult = service.run(realRequest(true));

        assertThat(defaultResult.provider().allowMemoryWrite()).isFalse();
        assertThat(defaultResult.memoryFeedback().summary().get("memoryWrite").toString())
            .contains("requested=false", "enabled=false", "actualWritePerformed=false", "providerMode=REAL");
        assertThat(explicitResult.provider().allowMemoryWrite()).isTrue();
        assertThat(explicitResult.memoryFeedback().summary().get("memoryWrite").toString())
            .contains(
                "requested=true",
                "enabled=true",
                "actualWritePerformed=false",
                "providerMode=REAL",
                "redacted=true"
            );
    }

    @Test
    void demoConsoleStaticPageDoesNotEmbedSensitiveFixtureOrProviderValues() throws Exception {
        var html = Files.readString(Path.of("src/main/resources/v4-demo-console/index.html"));

        assertThat(html)
            .contains("allowMemoryWrite: false")
            .doesNotContain("sk-test-secret")
            .doesNotContain("real-secret-token")
            .doesNotContain("unredacted-fixture-secret");
    }

    private DemoRunRequest realRequest(boolean allowMemoryWrite) {
        return new DemoRunRequest(
            "order-suite-demo",
            DemoRunProviderMode.REAL,
            "manual-real-llm",
            outputDirectory,
            false,
            allowMemoryWrite,
            List.of("JSON_REPORT", "MARKDOWN_REPORT")
        );
    }

    private Path artifactPath(DemoRunResult result, String artifactType) {
        return result.artifacts().stream()
            .filter(artifact -> artifact.artifactType().equals(artifactType))
            .findFirst()
            .map(artifact -> Path.of(artifact.path()))
            .orElseThrow();
    }
}
