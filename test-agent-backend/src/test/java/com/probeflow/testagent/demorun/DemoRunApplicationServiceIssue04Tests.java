package com.probeflow.testagent.demorun;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentHarness;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoRunApplicationServiceIssue04Tests {

    @TempDir
    Path outputDirectory;

    @Test
    void realLlmModeRequiresExplicitManualRunProfile() {
        var service = new DemoRunApplicationService();

        var result = service.run(new DemoRunRequest(
            "order-suite-demo",
            DemoRunProviderMode.REAL,
            "local-demo",
            outputDirectory
        ));

        assertThat(result.status()).isEqualTo(DemoRunStatus.REJECTED);
        assertThat(result.providerMode()).isEqualTo(DemoRunProviderMode.REAL);
        assertThat(result.usesRealLlm()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(result.errors().summary().toString()).contains("runProfile=manual-real-llm");
    }

    @Test
    void missingRealLlmConfigReturnsClearRejectedDemoResultWithoutExternalAccess() {
        var service = new DemoRunApplicationService();

        var result = service.run(realRequest(false));

        assertThat(result.status()).isEqualTo(DemoRunStatus.REJECTED);
        assertThat(result.provider().llmCalls()).isEmpty();
        assertThat(result.usesRealLlm()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(result.diagnostics())
            .extracting(DemoRunDiagnosticView::code)
            .contains("REAL_LLM_NOT_AVAILABLE");
        assertThat(result.errors().summary().toString())
            .contains("endpoint", "key", "model", "timeoutMs", "maxTokens", "costLimitCents")
            .doesNotContain("sk-test-secret");
    }

    @Test
    void realLlmPreflightFailuresAreClassifiedIntoDemoResultCallSummary() {
        var service = new DemoRunApplicationService(
            ManualSuiteAgentHarness.defaults(),
            (request, input) -> new DemoRunRealLlmProbeResult(
                DemoRunRealLlmProbeStatus.FAILED,
                "provider timed out",
                List.of(call("TIMEOUT", "provider timed out")),
                Map.of("providerTraceId", "trace-timeout")
            )
        );

        var result = service.run(realRequest(false));

        assertThat(result.status()).isEqualTo(DemoRunStatus.REJECTED);
        assertThat(result.provider().llmCalls())
            .singleElement()
            .satisfies(call -> {
                assertThat(call.purpose()).isEqualTo("V4_DEMO_REAL_LLM_PREFLIGHT");
                assertThat(call.status()).isEqualTo("FAILED");
                assertThat(call.model()).isEqualTo("real-model");
                assertThat(call.totalTokens()).isEqualTo(12);
                assertThat(call.errorType()).isEqualTo("TIMEOUT");
            });
        assertThat(result.errors().summary().toString()).contains("provider timed out");
    }

    @Test
    void policyBlockedRealLlmCallIsRejectedWithAuditableReason() {
        var service = new DemoRunApplicationService(
            ManualSuiteAgentHarness.defaults(),
            (request, input) -> new DemoRunRealLlmProbeResult(
                DemoRunRealLlmProbeStatus.BLOCKED,
                "real provider disabled by policy",
                List.of(call("POLICY_BLOCKED", "real provider disabled by policy")),
                Map.of("policy", "allow-real-providers=false")
            )
        );

        var result = service.run(realRequest(false));

        assertThat(result.status()).isEqualTo(DemoRunStatus.REJECTED);
        assertThat(result.provider().llmCalls()).singleElement()
            .extracting(DemoRunLlmCallSummary::errorType)
            .isEqualTo("POLICY_BLOCKED");
        assertThat(result.errors().summary().toString()).contains("allow-real-providers=false");
    }

    @Test
    void successfulRealLlmPreflightRunsExistingManualSuiteHarness() {
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
                    10,
                    5,
                    15,
                    "NONE",
                    null,
                    Map.of("providerTraceId", "trace-success")
                )),
                Map.of()
            )
        );

        var result = service.run(realRequest(false));

        assertThat(result.status()).isEqualTo(DemoRunStatus.COMPLETED);
        assertThat(result.usesRealLlm()).isTrue();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(result.tools().summary().toString()).contains("Manual Suite Agent Harness");
        assertThat(result.provider().llmCalls()).singleElement()
            .extracting(DemoRunLlmCallSummary::status)
            .isEqualTo("SUCCESS");
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

    private DemoRunLlmCallSummary call(String errorType, String message) {
        return new DemoRunLlmCallSummary(
            "V4_DEMO_REAL_LLM_PREFLIGHT",
            "FAILED",
            "manual-real",
            "real-model",
            7,
            5,
            12,
            errorType,
            message,
            Map.of("requestHash", "abc123")
        );
    }
}
