package com.probeflow.testagent.manualsuiteagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualSuiteAgentHarnessIssue02Tests {

    @TempDir
    private Path outputDir;

    @Test
    void orderSuiteFixtureInitializesExistingDomainInputsAndRunsThroughFakeHttpPath() throws Exception {
        var harness = ManualSuiteAgentHarness.defaults();

        var first = harness.run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));
        var second = harness.run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        assertThat(first.status()).isEqualTo(ManualSuiteAgentRunStatus.COMPLETED);
        assertThat(first.fixtureId()).isEqualTo("order-suite-demo");
        assertThat(first.fixtureVersion()).isEqualTo("2026.07.order.v1");
        assertThat(first.usesExternalHttp()).isFalse();
        assertThat(first.sections())
            .extracting(ManualSuiteAgentSectionSummary::sectionId)
            .contains("task-context", "api-spec-input", "test-case-input", "execution-result");

        @SuppressWarnings("unchecked")
        var task = (Map<String, Object>) first.metadata().get("task");
        assertThat(task)
            .containsEntry("taskId", "task-v3-order-suite-demo")
            .containsEntry("taskName", "V3 Manual Suite Agent order demo")
            .containsEntry("taskType", "REGRESSION")
            .containsEntry("sourceType", "MANUAL")
            .containsEntry("promotionMode", "AUTO");
        @SuppressWarnings("unchecked")
        var targetApiSpecIds = (List<String>) task.get("targetApiSpecIds");
        assertThat(targetApiSpecIds)
            .containsExactly("api-order-create", "api-order-pay", "api-order-query");

        @SuppressWarnings("unchecked")
        var apiSpecs = (Iterable<Map<String, Object>>) first.metadata().get("apiSpecs");
        assertThat(apiSpecs)
            .extracting(api -> api.get("path"))
            .containsExactly("/api/orders", "/api/orders/{orderId}/payments", "/api/orders/{orderId}");

        @SuppressWarnings("unchecked")
        var testCases = (Iterable<Map<String, Object>>) first.metadata().get("testCases");
        assertThat(testCases)
            .extracting(testCase -> testCase.get("mode"))
            .containsExactly("SUITE");

        @SuppressWarnings("unchecked")
        var execution = (Map<String, Object>) first.metadata().get("executionSummary");
        assertThat(execution)
            .containsEntry("environment", "fixture-local")
            .containsEntry("gateway", "FAKE_HTTP")
            .containsEntry("caseCount", 1)
            .containsEntry("stepCount", 3)
            .containsEntry("passed", 3)
            .containsEntry("failed", 0)
            .containsEntry("skipped", 0)
            .containsEntry("blocked", 0);
        @SuppressWarnings("unchecked")
        var stepResults = (List<Map<String, Object>>) execution.get("stepResults");
        assertThat(stepResults.stream().map(step -> step.get("status")).toList())
            .containsExactly("PASSED", "PASSED", "PASSED");
        @SuppressWarnings("unchecked")
        var responseHighlights = (List<Map<String, Object>>) execution.get("responseHighlights");
        assertThat(responseHighlights.stream().map(highlight -> highlight.get("summary")).toList())
            .containsExactly(
                "order ORD-1001 created",
                "payment PAY-9001 accepted",
                "order ORD-1001 is PAID"
            );

        assertThat(second.metadata().get("executionSummary"))
            .usingRecursiveComparison()
            .isEqualTo(first.metadata().get("executionSummary"));

        var jsonPath = artifactPath(first, "JSON_REPORT");
        var markdownPath = artifactPath(first, "MARKDOWN_REPORT");
        var jsonText = Files.readString(jsonPath);
        var markdown = Files.readString(markdownPath);
        assertThat(jsonText).doesNotContain("order-demo-token", "Bearer order-demo-token", "session-cookie-secret");
        assertThat(markdown).doesNotContain("order-demo-token", "Bearer order-demo-token", "session-cookie-secret");

        var json = new ObjectMapper().readTree(jsonPath.toFile());
        assertThat(json.at("/run/usesExternalHttp").asBoolean()).isFalse();
        assertThat(json.at("/metadata/executionSummary/gateway").asText()).isEqualTo("FAKE_HTTP");
        assertThat(json.at("/metadata/executionSummary/passed").asInt()).isEqualTo(3);
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
