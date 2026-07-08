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
    void orderFixtureExposesV3SectionsWithExplicitSourceMarkersInJsonAndMarkdown() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        var suiteDraft = section(result, "generated-suite-draft");
        assertThat(suiteDraft.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(suiteDraft.summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("readinessStatus", "READY")
            .containsEntry("dependencyCount", 2);
        @SuppressWarnings("unchecked")
        var suiteDraftSteps = (List<Map<String, Object>>) suiteDraft.summary().get("steps");
        assertThat(suiteDraftSteps.stream().map(step -> step.get("stepName")).toList())
            .containsExactly("Create order", "Pay order", "Query order");

        var variableAudit = section(result, "variable-audit");
        assertThat(variableAudit.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(variableAudit.summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("runtime", "ExecutionContext");
        @SuppressWarnings("unchecked")
        var auditEvents = (List<Map<String, Object>>) variableAudit.summary().get("auditEvents");
        assertThat(auditEvents)
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "PRODUCTION")
                .containsEntry("stepId", "create-order")
                .containsEntry("targetScope", "suite")
                .containsEntry("targetKey", "orderId"))
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "CONSUMPTION")
                .containsEntry("stepId", "pay-order")
                .containsEntry("expression", "${suite.orderId}"))
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "CONSUMPTION")
                .containsEntry("stepId", "query-order")
                .containsEntry("expression", "${suite.orderId}"));

        var failureAnalysis = section(result, "failure-analysis");
        assertThat(failureAnalysis.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(failureAnalysis.status()).isEqualTo("PASSED");
        assertThat(failureAnalysis.summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("classification", "NONE")
            .containsEntry("rootStep", null)
            .containsEntry("recoveryActionType", "NO_ACTION");

        var memoryFeedback = section(result, "memory-feedback");
        assertThat(memoryFeedback.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(memoryFeedback.summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("candidateStatus", "REJECTED")
            .containsEntry("classification", "NONE")
            .containsEntry("writesLongTermMemory", false)
            .containsEntry("rejectionReason", "suite-failure-not-learnable");

        var evaluationComparison = section(result, "evaluation-comparison");
        assertThat(evaluationComparison.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(evaluationComparison.summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("dataset", "v3-phase-6-suite-agent-capability")
            .containsEntry("runStatus", "PASSED")
            .containsEntry("providerMode", "DETERMINISTIC_FAKE")
            .containsEntry("usesRealProvider", false);

        var json = new ObjectMapper().readTree(Files.readString(artifactPath(result, "JSON_REPORT")));
        assertSectionSource(json, "generated-suite-draft", "REAL", "real");
        assertSectionSource(json, "variable-audit", "REAL", "real");
        assertSectionSource(json, "failure-analysis", "REAL", "real");
        assertSectionSource(json, "memory-feedback", "REAL", "real");
        assertSectionSource(json, "evaluation-comparison", "REAL", "real");

        var markdown = Files.readString(artifactPath(result, "MARKDOWN_REPORT"));
        assertThat(markdown)
            .contains("generated-suite-draft")
            .contains("variable-audit")
            .contains("failure-analysis")
            .contains("memory-feedback")
            .contains("evaluation-comparison")
            .contains("V3-3 DependencyLinker")
            .contains("ExecutionContext")
            .contains("create-order produces suite.orderId")
            .contains("pay-order consumes ${suite.orderId}")
            .contains("query-order consumes ${suite.orderId}")
            .contains("Classification: NONE")
            .contains("No failure follow-up is required")
            .contains("memory-feedback (REAL")
            .contains("Candidate status: REJECTED")
            .contains("evaluation-comparison (REAL, PASSED");
        assertThat(markdown).doesNotContain("PENDING_RUNTIME", "awaits V3-4");
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
