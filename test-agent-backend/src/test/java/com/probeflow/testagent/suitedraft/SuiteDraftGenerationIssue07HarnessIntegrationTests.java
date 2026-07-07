package com.probeflow.testagent.suitedraft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentArtifactReference;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentHarness;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunRequest;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunResult;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSource;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteDraftGenerationIssue07HarnessIntegrationTests {

    @TempDir
    private Path outputDir;

    @Test
    void orderSuiteHarnessExposesRealGeneratedSuiteDraft() {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        var generated = section(result, "generated-suite-draft");
        assertThat(generated.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(generated.status()).isEqualTo("READY");
        assertThat(generated.summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("flowId", "flow-create-order-pay-order-query-order")
            .containsEntry("scenarioName", "Create order -> Pay order -> Query order")
            .containsEntry("stepCount", 3)
            .containsEntry("dependencyCount", 2)
            .containsEntry("readinessStatus", "READY")
            .containsEntry("diagnosticCount", 0);

        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) generated.summary().get("steps");
        assertThat(steps)
            .extracting(step -> step.get("stepId"))
            .containsExactly("create-order", "pay-order", "query-order");
        assertThat(steps)
            .allSatisfy(step -> assertThat(step)
                .containsKeys("stepId", "stepName", "order", "apiSpecId", "critical", "sourceRefs", "readinessStatus"));

        @SuppressWarnings("unchecked")
        var extractRules = (List<Map<String, Object>>) generated.summary().get("extractRules");
        assertThat(extractRules)
            .singleElement()
            .satisfies(rule -> assertThat(rule)
                .containsEntry("ruleId", "rule-create-order-orderId")
                .containsEntry("producerStepId", "create-order")
                .containsEntry("sourcePath", "$.data.orderId")
                .containsEntry("targetKey", "orderId"));

        @SuppressWarnings("unchecked")
        var references = (List<Map<String, Object>>) generated.summary().get("variableReferences");
        assertThat(references)
            .extracting(reference -> reference.get("consumerStepId"))
            .containsExactly("pay-order", "query-order");
        assertThat(references)
            .allSatisfy(reference -> assertThat(reference)
                .containsEntry("targetKey", "orderId")
                .containsEntry("referenceExpression", "${suite.orderId}"));

        assertThat(result.metadata().get("generatedSuiteDraft")).isEqualTo(generated.summary());
        var variableAudit = section(result, "variable-audit");
        assertThat(variableAudit.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(variableAudit.status()).isEqualTo("PASSED");
        assertThat(variableAudit.summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("runtime", "ExecutionContext");
    }

    @Test
    void generatedSuiteDraftIsConsistentAcrossJsonMarkdownAndStableAcrossRuns() throws Exception {
        var first = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));
        var second = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        var firstGenerated = section(first, "generated-suite-draft").summary();
        var secondGenerated = section(second, "generated-suite-draft").summary();
        assertThat(secondGenerated).isEqualTo(firstGenerated);

        var json = new ObjectMapper().readTree(Files.readString(artifactPath(first, "JSON_REPORT")));
        var markdown = Files.readString(artifactPath(first, "MARKDOWN_REPORT"));
        var jsonGenerated = jsonSection(json, "generated-suite-draft");

        assertThat(jsonGenerated.get("source").asText()).isEqualTo("REAL");
        assertThat(jsonGenerated.at("/summary/flowId").asText()).isEqualTo(firstGenerated.get("flowId"));
        assertThat(jsonGenerated.at("/summary/readinessStatus").asText()).isEqualTo("READY");
        assertThat(jsonGenerated.toString())
            .contains("create-order")
            .contains("pay-order")
            .contains("query-order")
            .contains("dep-create-order-to-pay-order-orderId")
            .contains("rule-create-order-orderId")
            .contains("${suite.orderId}");

        assertThat(markdown)
            .contains("generated-suite-draft (REAL, READY)")
            .contains(firstGenerated.get("flowId").toString())
            .contains("create-order")
            .contains("dep-create-order-to-query-order-orderId")
            .contains("rule-create-order-orderId")
            .contains("${suite.orderId}")
            .contains("variable-audit (REAL, PASSED)")
            .contains("Runtime: ExecutionContext");
        assertNoSensitiveValues(Files.readString(artifactPath(first, "JSON_REPORT")));
        assertNoSensitiveValues(markdown);
    }

    private ManualSuiteAgentSectionSummary section(ManualSuiteAgentRunResult result, String sectionId) {
        return result.sections().stream()
            .filter(section -> section.sectionId().equals(sectionId))
            .findFirst()
            .orElseThrow();
    }

    private JsonNode jsonSection(JsonNode json, String sectionId) {
        for (var section : json.get("sections")) {
            if (section.get("sectionId").asText().equals(sectionId)) {
                return section;
            }
        }
        throw new IllegalArgumentException("Missing section " + sectionId);
    }

    private Path artifactPath(ManualSuiteAgentRunResult result, String artifactType) {
        return result.artifacts().stream()
            .filter(artifact -> artifact.artifactType().equals(artifactType))
            .findFirst()
            .map(ManualSuiteAgentArtifactReference::path)
            .map(Path::of)
            .orElseThrow();
    }

    private void assertNoSensitiveValues(String text) {
        assertThat(text)
            .doesNotContain(
                "order-demo-token",
                "Bearer order-demo-token",
                "session-cookie-secret",
                "invalid-fixture-token",
                "Bearer invalid-fixture-token",
                "invalid-password",
                "secret-cookie",
                "api-key-123"
            );
    }
}
