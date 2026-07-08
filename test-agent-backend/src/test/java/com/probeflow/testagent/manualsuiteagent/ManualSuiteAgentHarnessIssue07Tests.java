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

class ManualSuiteAgentHarnessIssue07Tests {

    @TempDir
    private Path outputDir;

    @Test
    void orderSuiteDemoUsesExecutionContextRuntimeForRealVariableAudit() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        var execution = section(result, "execution-result");
        assertThat(execution.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(execution.summary())
            .containsEntry("runtime", "ExecutionContext")
            .containsEntry("runtimeInput", "generated-suite-draft")
            .containsEntry("gateway", "FAKE_HTTP")
            .containsEntry("usesExternalHttp", false)
            .containsEntry("passed", 3)
            .containsEntry("blocked", 0);

        @SuppressWarnings("unchecked")
        var stepResults = (List<Map<String, Object>>) execution.summary().get("stepResults");
        assertThat(stepResults.stream().map(step -> step.get("status")).toList())
            .containsExactly("PASSED", "PASSED", "PASSED");
        assertThat(pathFor(stepResults, "pay-order")).isEqualTo("/api/orders/ORD-1001/payments");
        assertThat(pathFor(stepResults, "query-order")).isEqualTo("/api/orders/ORD-1001");

        var variableAudit = section(result, "variable-audit");
        assertThat(variableAudit.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(variableAudit.status()).isEqualTo("PASSED");
        assertThat(variableAudit.summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("runtime", "ExecutionContext");

        @SuppressWarnings("unchecked")
        var auditEvents = (List<Map<String, Object>>) variableAudit.summary().get("auditEvents");
        assertThat(auditEvents)
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "PRODUCTION")
                .containsEntry("stepId", "create-order")
                .containsEntry("sourcePath", "$.data.orderId")
                .containsEntry("targetScope", "suite")
                .containsEntry("targetKey", "orderId")
                .containsEntry("success", true))
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "CONSUMPTION")
                .containsEntry("stepId", "pay-order")
                .containsEntry("expression", "${suite.orderId}")
                .containsEntry("location", "request.path")
                .containsEntry("resolved", true))
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "CONSUMPTION")
                .containsEntry("stepId", "query-order")
                .containsEntry("expression", "${suite.orderId}")
                .containsEntry("location", "request.path")
                .containsEntry("resolved", true));

        @SuppressWarnings("unchecked")
        var variableAuditSummary = (Map<String, Object>) variableAudit.summary().get("variableAuditSummary");
        assertThat(variableAuditSummary)
            .containsEntry("productionEvents", 1L)
            .containsEntry("consumptionEvents", 2L)
            .containsEntry("failureEvents", 0L);
        assertThat(variableAudit.summary().get("runtimeDiagnostics")).isEqualTo(List.of());
    }

    @Test
    void variableAuditJsonMarkdownAndLaterPhaseSlotsStayConsistentAndRedacted() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        var jsonText = Files.readString(artifactPath(result, "JSON_REPORT"));
        var markdown = Files.readString(artifactPath(result, "MARKDOWN_REPORT"));
        var json = new ObjectMapper().readTree(jsonText);
        var variableAuditJson = section(json, "variable-audit");

        assertThat(variableAuditJson.get("source").asText()).isEqualTo("REAL");
        assertThat(variableAuditJson.at("/summary/sourceMarker").asText()).isEqualTo("real");
        assertThat(variableAuditJson.at("/summary/runtime").asText()).isEqualTo("ExecutionContext");
        assertThat(json.at("/metadata/executionSummary/runtimeInput").asText()).isEqualTo("generated-suite-draft");

        assertThat(markdown)
            .contains("variable-audit (REAL, PASSED)")
            .contains("Runtime: ExecutionContext")
            .contains("create-order produces suite.orderId")
            .contains("pay-order consumes ${suite.orderId}")
            .contains("query-order consumes ${suite.orderId}");
        assertThat(jsonText).contains("create-order produces suite.orderId");

        var failureAnalysis = section(result, "failure-analysis");
        assertThat(failureAnalysis.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(failureAnalysis.status()).isEqualTo("PASSED");
        assertThat(failureAnalysis.summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("classification", "NONE")
            .containsEntry("rootStep", null)
            .containsEntry("affectedSteps", List.of())
            .containsEntry("nextSuggestion", "No failure follow-up is required.")
            .containsEntry("recoveryActionType", "NO_ACTION");
        assertThat(section(result, "memory-feedback").source()).isEqualTo(ManualSuiteAgentSectionSource.STAGED);
        assertThat(section(result, "evaluation-comparison").source()).isEqualTo(ManualSuiteAgentSectionSource.NOT_RUN);
        assertThat(jsonText).doesNotContain("PENDING_RUNTIME");
        assertThat(markdown).doesNotContain("PENDING_RUNTIME");
        assertThat(json.at("/sections/" + sectionIndex(json, "failure-analysis") + "/summary/suiteFailureAnalysis/classification").asText())
            .isEqualTo("NONE");
        assertThat(markdown)
            .contains("failure-analysis (REAL, PASSED)")
            .contains("Classification: NONE")
            .contains("Root cause:")
            .contains("No failure follow-up is required.");

        assertNoSensitiveValues(jsonText);
        assertNoSensitiveValues(markdown);
    }

    @Test
    void failureAnalysisExplainsVariableExtractionFailureFixture() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-variable-extraction-failure", outputDir));

        var failureAnalysis = section(result, "failure-analysis");
        assertThat(failureAnalysis.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(failureAnalysis.status()).isEqualTo("REVIEW");
        assertThat(failureAnalysis.summary())
            .containsEntry("classification", "VARIABLE_EXTRACTION_FAILURE")
            .containsEntry("rootStep", "create-order")
            .containsEntry("riskLevel", "HIGH")
            .containsEntry("confidence", "0.90")
            .containsEntry("requiresHumanReview", true)
            .containsEntry("recoveryActionType", "FIX_EXTRACT_RULE");
        assertThat(stringList(failureAnalysis.summary().get("affectedSteps")))
            .containsExactly("pay-order", "query-order");
        assertThat(failureAnalysis.summary().get("nextSuggestion").toString())
            .contains("response field path")
            .contains("extractRule");
        assertThat(stringList(failureAnalysis.summary().get("evidence")))
            .anySatisfy(evidence -> assertThat(evidence).contains("runtimeDiagnostic"))
            .anySatisfy(evidence -> assertThat(evidence).contains("variableAuditEvent"));
        assertThat(section(result, "execution-result").summary())
            .containsEntry("status", "BLOCKED")
            .containsEntry("blocked", 1);
    }

    @Test
    void failureAnalysisExplainsVariableResolutionFailureFixture() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-variable-resolution-failure", outputDir));

        var failureAnalysis = section(result, "failure-analysis");
        assertThat(failureAnalysis.summary())
            .containsEntry("classification", "VARIABLE_RESOLUTION_FAILURE")
            .containsEntry("rootStep", "pay-order")
            .containsEntry("recoveryActionType", "FIX_VARIABLE_REFERENCE");
        assertThat(stringList(failureAnalysis.summary().get("affectedSteps")))
            .containsExactly("query-order");
        assertThat(failureAnalysis.summary().get("nextSuggestion").toString())
            .contains("missing variable")
            .contains("variable reference");
        assertThat(stringList(failureAnalysis.summary().get("evidence")))
            .anySatisfy(evidence -> assertThat(evidence).contains("runtimeDiagnostic"))
            .anySatisfy(evidence -> assertThat(evidence).contains("variableAuditEvent"));
    }

    @Test
    void failureAnalysisExplainsPrerequisiteFailureSkippedDownstreamFixture() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-prerequisite-failure", outputDir));

        var failureAnalysis = section(result, "failure-analysis");
        assertThat(failureAnalysis.summary())
            .containsEntry("classification", "PREREQUISITE_STEP_FAILURE")
            .containsEntry("rootStep", "create-order")
            .containsEntry("recoveryActionType", "WAIT_FOR_SERVICE_OR_DATA_FIX");
        assertThat(stringList(failureAnalysis.summary().get("affectedSteps")))
            .containsExactly("pay-order", "query-order");
        assertThat(failureAnalysis.summary().get("nextSuggestion").toString())
            .contains("prerequisite step create-order");
        assertThat(section(result, "execution-result").summary())
            .containsEntry("status", "FAILED")
            .containsEntry("failed", 1)
            .containsEntry("skipped", 2);
    }

    @Test
    void failureAnalysisExplainsDownstreamApiFailureFixtureAndReportsConsistentArtifacts() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-downstream-api-failure", outputDir));
        var failureAnalysis = section(result, "failure-analysis");

        assertThat(failureAnalysis.summary())
            .containsEntry("classification", "DOWNSTREAM_API_FAILURE")
            .containsEntry("rootStep", "pay-order")
            .containsEntry("recoveryActionType", "WAIT_FOR_SERVICE_OR_DATA_FIX");
        assertThat(stringList(failureAnalysis.summary().get("affectedSteps")))
            .containsExactly("query-order");
        assertThat(failureAnalysis.summary().get("nextSuggestion").toString())
            .contains("downstream API")
            .contains("prerequisite variables");
        assertThat(stringList(failureAnalysis.summary().get("evidence")))
            .anySatisfy(evidence -> assertThat(evidence).contains("statusCode=503"));

        var jsonText = Files.readString(artifactPath(result, "JSON_REPORT"));
        var markdown = Files.readString(artifactPath(result, "MARKDOWN_REPORT"));
        var json = new ObjectMapper().readTree(jsonText);
        var failureJson = section(json, "failure-analysis");
        assertThat(failureJson.get("source").asText()).isEqualTo("REAL");
        assertThat(failureJson.at("/summary/classification").asText()).isEqualTo("DOWNSTREAM_API_FAILURE");
        assertThat(failureJson.at("/summary/suiteFailureAnalysis/classification").asText()).isEqualTo("DOWNSTREAM_API_FAILURE");
        assertThat(markdown)
            .contains("failure-analysis (REAL, REVIEW)")
            .contains("Classification: DOWNSTREAM_API_FAILURE")
            .contains("Root cause:")
            .contains("Affected steps: [query-order]")
            .contains("Next suggestion: Inspect the downstream API response");
        assertNoSensitiveValues(jsonText);
        assertNoSensitiveValues(markdown);
    }

    private ManualSuiteAgentSectionSummary section(ManualSuiteAgentRunResult result, String sectionId) {
        return result.sections().stream()
            .filter(section -> section.sectionId().equals(sectionId))
            .findFirst()
            .orElseThrow();
    }

    private JsonNode section(JsonNode json, String sectionId) {
        for (var section : json.get("sections")) {
            if (sectionId.equals(section.get("sectionId").asText())) {
                return section;
            }
        }
        throw new IllegalArgumentException("Section not found: " + sectionId);
    }

    private int sectionIndex(JsonNode json, String sectionId) {
        var sections = json.get("sections");
        for (var index = 0; index < sections.size(); index++) {
            if (sectionId.equals(sections.get(index).get("sectionId").asText())) {
                return index;
            }
        }
        throw new IllegalArgumentException("Section not found: " + sectionId);
    }

    @SuppressWarnings("unchecked")
    private String pathFor(List<Map<String, Object>> stepResults, String stepId) {
        var step = stepResults.stream()
            .filter(item -> stepId.equals(item.get("stepId")))
            .findFirst()
            .orElseThrow();
        var requestSnapshot = (Map<String, Object>) step.get("requestSnapshot");
        return requestSnapshot.get("path").toString();
    }

    private Path artifactPath(ManualSuiteAgentRunResult result, String artifactType) {
        return result.artifacts().stream()
            .filter(artifact -> artifact.artifactType().equals(artifactType))
            .findFirst()
            .map(ManualSuiteAgentArtifactReference::path)
            .map(Path::of)
            .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        return (List<String>) value;
    }

    private void assertNoSensitiveValues(String text) {
        assertThat(text)
            .doesNotContain(
                "order-demo-token",
                "Bearer order-demo-token",
                "session-cookie-secret",
                "Authorization: Bearer",
                "api-key-123"
            )
            .contains("[REDACTED]");
    }
}
