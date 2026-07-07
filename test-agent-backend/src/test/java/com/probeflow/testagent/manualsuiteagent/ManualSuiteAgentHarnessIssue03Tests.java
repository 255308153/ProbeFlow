package com.probeflow.testagent.manualsuiteagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualSuiteAgentHarnessIssue03Tests {

    @TempDir
    private Path outputDir;

    @Test
    void orderFixtureExposesStagedV3SectionsWithExplicitSourceMarkersInJsonAndMarkdown() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        var suiteDraft = section(result, "generated-suite-draft");
        assertThat(suiteDraft.source()).isEqualTo(ManualSuiteAgentSectionSource.FIXTURE);
        assertThat(suiteDraft.summary())
            .containsEntry("scenarioName", "Order checkout happy path")
            .containsEntry("sourceMarker", "fixture");
        @SuppressWarnings("unchecked")
        var suiteDraftSteps = (List<Map<String, Object>>) suiteDraft.summary().get("steps");
        assertThat(suiteDraftSteps.stream().map(step -> step.get("stepName")).toList())
            .containsExactly("Create order", "Pay order", "Query order");

        var variableAudit = section(result, "variable-audit");
        assertThat(variableAudit.source()).isEqualTo(ManualSuiteAgentSectionSource.PENDING_RUNTIME);
        assertThat(variableAudit.summary())
            .containsEntry("sourceMarker", "pending-runtime")
            .containsEntry("targetScope", "suite");
        @SuppressWarnings("unchecked")
        var auditEvents = (List<Map<String, Object>>) variableAudit.summary().get("auditEvents");
        assertThat(auditEvents.stream().map(event -> event.get("targetKey")).toList())
            .contains("orderId", "paymentId");

        var failureAnalysis = section(result, "failure-analysis");
        assertThat(failureAnalysis.source()).isEqualTo(ManualSuiteAgentSectionSource.STAGED);
        assertThat(failureAnalysis.summary())
            .containsEntry("sourceMarker", "staged")
            .containsEntry("rootStep", "pay-order")
            .containsEntry("failureType", "STAGED_SUITE_FAILURE_SLOT");

        var memoryFeedback = section(result, "memory-feedback");
        assertThat(memoryFeedback.source()).isEqualTo(ManualSuiteAgentSectionSource.STAGED);
        assertThat(memoryFeedback.summary())
            .containsEntry("sourceMarker", "staged")
            .containsEntry("candidateCount", 1)
            .containsEntry("sourceType", "FIXTURE_EXECUTION_SUMMARY")
            .containsEntry("confidence", "0.80");

        var evaluationComparison = section(result, "evaluation-comparison");
        assertThat(evaluationComparison.source()).isEqualTo(ManualSuiteAgentSectionSource.NOT_RUN);
        assertThat(evaluationComparison.summary())
            .containsEntry("sourceMarker", "not-run")
            .containsEntry("providerMode", "DETERMINISTIC_FAKE")
            .containsEntry("fixtureId", "order-suite-demo");

        var json = new ObjectMapper().readTree(Files.readString(artifactPath(result, "JSON_REPORT")));
        assertSectionSource(json, "generated-suite-draft", "FIXTURE", "fixture");
        assertSectionSource(json, "variable-audit", "PENDING_RUNTIME", "pending-runtime");
        assertSectionSource(json, "failure-analysis", "STAGED", "staged");
        assertSectionSource(json, "memory-feedback", "STAGED", "staged");
        assertSectionSource(json, "evaluation-comparison", "NOT_RUN", "not-run");

        var markdown = Files.readString(artifactPath(result, "MARKDOWN_REPORT"));
        assertThat(markdown)
            .contains("generated-suite-draft")
            .contains("variable-audit")
            .contains("failure-analysis")
            .contains("memory-feedback")
            .contains("evaluation-comparison")
            .contains("awaits V3-2/V3-3")
            .contains("awaits V3-4")
            .contains("awaits V3-5")
            .contains("awaits V3-6");
    }

    private ManualSuiteAgentSectionSummary section(ManualSuiteAgentRunResult result, String sectionId) {
        return result.sections().stream()
            .filter(section -> section.sectionId().equals(sectionId))
            .findFirst()
            .orElseThrow();
    }

    private void assertSectionSource(JsonNode json, String sectionId, String source, String sourceMarker) {
        var section = json.get("sections").findValuesAsText("sectionId").indexOf(sectionId);
        assertThat(section).isGreaterThanOrEqualTo(0);
        var node = json.get("sections").get(section);
        assertThat(node.get("source").asText()).isEqualTo(source);
        assertThat(node.get("summary").get("sourceMarker").asText()).isEqualTo(sourceMarker);
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
