package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentArtifactReference;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentHarness;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentProviderMode;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunRequest;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunResult;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunStatus;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSource;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V3Phase4AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @TempDir
    private Path outputDir;

    @Test
    void focusedTestsCoverAllV3Phase4RuntimeAcceptancePaths() throws Exception {
        var httpTestsPath = PROJECT_ROOT.resolve(
            "src/test/java/com/probeflow/testagent/httpexecution/HttpExecutionApplicationServiceTests.java"
        );
        var harnessTestsPath = PROJECT_ROOT.resolve(
            "src/test/java/com/probeflow/testagent/manualsuiteagent/ManualSuiteAgentHarnessIssue07Tests.java"
        );
        assertThat(httpTestsPath).exists().isRegularFile();
        assertThat(harnessTestsPath).exists().isRegularFile();

        var httpTests = Files.readString(httpTestsPath);
        assertContainsAll(httpTests, "runtime acceptance methods", List.of(
            "suiteExecutionContextExtractsOrderIdAndResolvesDownstreamRequests",
            "variableResolverCoversScopesPathsLocationsAndTypePreservation",
            "variableResolverBlocksMissingInvalidScopeAndInvalidExpressionBeforeTransport",
            "dryRunResolvesSuiteRequestTemplateWithoutCallingTransport",
            "dynamicValueProviderResolvesDeterministicFunctionsAcrossRequestTemplate",
            "unsupportedDynamicFunctionReturnsDiagnosticWithoutSideEffects",
            "responseExtractorReadsBodyJsonHeaderAndStatusCodeIntoRuntimeContext",
            "responseExtractorFallbackStrategiesWriteNullAndDefaultValues",
            "responseExtractorBlocksRequiredFailuresInvalidRulesAndUnsupportedSources",
            "variableWriteBackAuditsSuiteStepOverwriteFallbackAndConsumptionLifecycle",
            "variableWriteBackFailuresAndSensitiveValuesAreAuditedWithRedaction",
            "suiteDiagnosticsMarkMissingVariableBlockedAndSkipDependentsWithCause",
            "suiteDiagnosticsMarkRequiredExtractionFailureAndSkipDependentsWithCause",
            "dryRunResolvesVariablesButSkipsTransportExtractionWriteBackAndProductionAudit",
            "suiteExecutionContinuesAfterHttpFailureWhenStopOnCriticalFailureIsDisabled",
            "suiteStopsDependentStepsAfterFailedPrerequisiteWhenConfigured"
        ));
        assertContainsAll(httpTests, "runtime behavior snippets", List.of(
            "${suite.orderId}",
            "${step.create-order.orderId}",
            "${env.tenant}",
            "${task.externalTaskId}",
            "${case.customerId}",
            "${suite.items[0].id}",
            "BODY_JSON",
            "HEADER",
            "STATUS_CODE",
            "FAIL_FAST",
            "WRITE_NULL",
            "WRITE_DEFAULT",
            "UNSUPPORTED_EXTRACT_SOURCE",
            "UNSUPPORTED_WRITE_SCOPE",
            "DYNAMIC_VALUE_PROVIDER",
            "productionEvents",
            "consumptionEvents",
            "failureEvents",
            "[REDACTED]"
        ));

        var harnessTests = Files.readString(harnessTestsPath);
        assertContainsAll(harnessTests, "harness integration snippets", List.of(
            "orderSuiteDemoUsesExecutionContextRuntimeForRealVariableAudit",
            "variableAuditJsonMarkdownAndLaterPhaseSlotsStayConsistentAndRedacted",
            "runtimeInput\", \"generated-suite-draft",
            "variable-audit (REAL, PASSED)",
            "failure-analysis",
            "memory-feedback",
            "evaluation-comparison"
        ));
    }

    @Test
    void harnessUsesGeneratedSuiteDraftAsRuntimeInputAndKeepsLaterPhaseSlotsStaged() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        assertThat(result.status()).isEqualTo(ManualSuiteAgentRunStatus.COMPLETED);
        assertThat(result.providerMode()).isEqualTo(ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE);
        assertThat(result.usesRealProvider()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();

        var generatedSuiteDraft = section(result, "generated-suite-draft");
        assertThat(generatedSuiteDraft.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(generatedSuiteDraft.summary().toString())
            .contains("rule-create-order-orderId")
            .contains("$.data.orderId")
            .contains("${suite.orderId}");

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
        assertThat(stepPath(stepResults, "pay-order")).isEqualTo("/api/orders/ORD-1001/payments");
        assertThat(stepPath(stepResults, "query-order")).isEqualTo("/api/orders/ORD-1001");

        var variableAudit = section(result, "variable-audit");
        assertThat(variableAudit.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(variableAudit.status()).isEqualTo("PASSED");
        assertThat(variableAudit.summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("runtime", "ExecutionContext");
        @SuppressWarnings("unchecked")
        var variableAuditSummary = (Map<String, Object>) variableAudit.summary().get("variableAuditSummary");
        assertThat(variableAuditSummary)
            .containsEntry("productionEvents", 1L)
            .containsEntry("consumptionEvents", 2L)
            .containsEntry("failureEvents", 0L);

        assertThat(section(result, "failure-analysis").source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(section(result, "failure-analysis").summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("classification", "NONE")
            .containsEntry("recoveryActionType", "NO_ACTION");
        assertThat(section(result, "memory-feedback").source()).isEqualTo(ManualSuiteAgentSectionSource.STAGED);
        assertThat(section(result, "evaluation-comparison").source()).isEqualTo(ManualSuiteAgentSectionSource.NOT_RUN);
        assertThat(result.sections())
            .extracting(ManualSuiteAgentSectionSummary::sectionId)
            .doesNotContain(
                "suite-failure-analysis",
                "replanning",
                "human-in-the-loop",
                "v3-memory-feedback",
                "v3-agent-evaluation",
                "v3-light-console"
            );

        var jsonText = Files.readString(artifactPath(result, "JSON_REPORT"));
        var markdown = Files.readString(artifactPath(result, "MARKDOWN_REPORT"));
        var json = new ObjectMapper().readTree(jsonText);
        assertThat(json.at("/metadata/executionSummary/runtime").asText()).isEqualTo("ExecutionContext");
        assertThat(json.at("/metadata/executionSummary/runtimeInput").asText()).isEqualTo("generated-suite-draft");
        assertThat(section(json, "variable-audit").get("source").asText()).isEqualTo("REAL");
        assertThat(markdown)
            .contains("variable-audit (REAL, PASSED)")
            .contains("Runtime: ExecutionContext")
            .contains("failure-analysis (REAL, PASSED)")
            .contains("Classification: NONE")
            .contains("memory-feedback (STAGED")
            .contains("evaluation-comparison (NOT_RUN");
        assertThat(jsonText).doesNotContain("PENDING_RUNTIME");
        assertThat(markdown).doesNotContain("PENDING_RUNTIME");
        assertNoSensitiveValues(jsonText);
        assertNoSensitiveValues(markdown);
    }

    @Test
    void v3Phase4RuntimePackagesDoNotImplementGenerationRecoveryMemoryConsoleOrExternalProviders() throws Exception {
        var runtimeRoot = PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/suiteruntime");
        var httpExecutionRoot = PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/httpexecution");
        var runtimeAndExecutionSources = sourceText(runtimeRoot) + "\n" + sourceText(httpExecutionRoot);

        assertThat(javaFileNames(runtimeRoot))
            .contains(
                "ExecutionContext.java",
                "VariableResolver.java",
                "DynamicValueProvider.java",
                "ResponseExtractor.java",
                "VariableWriteBackService.java",
                "RuntimeDiagnostic.java",
                "RuntimeRedactor.java"
            );
        assertThat(presentTerms(runtimeAndExecutionSources, List.of(
            "com.probeflow.testagent.businessflowdiscovery",
            "BusinessFlowCandidate",
            "BusinessFlowDiscoveryService",
            "com.probeflow.testagent.suitedraft",
            "SuiteDraftGenerationService",
            "DependencyLinker",
            "SuiteVariableReference",
            "SuiteVariableDependency",
            "com.probeflow.testagent.failureanalysis",
            "com.probeflow.testagent.replanning",
            "com.probeflow.testagent.humanintheloop",
            "com.probeflow.testagent.agentmemoryfeedback",
            "com.probeflow.testagent.agentevaluation",
            "SuiteFailureAnalysis",
            "FailureClassifier",
            "failureClassification",
            "rootStep",
            "affectedDownstream",
            "nextSuggestion",
            "Observation",
            "Replanning",
            "HumanInTheLoop",
            "MemoryFeedback",
            "AgentEvaluation",
            "LightConsole",
            "java.net.http",
            "RestTemplate",
            "WebClient",
            "HttpURLConnection",
            "EmbeddingService",
            "EmbeddingClient",
            "OpenAI",
            "Anthropic",
            "pgvector",
            "BM25",
            "QueryRewrite",
            "Small-to-Big",
            "SmallToBig",
            "mem0"
        ))).isEmpty();
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

    @SuppressWarnings("unchecked")
    private String stepPath(List<Map<String, Object>> stepResults, String stepId) {
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

    private String sourceText(Path sourceRoot) throws Exception {
        if (!Files.exists(sourceRoot)) {
            return "";
        }
        try (var paths = Files.walk(sourceRoot)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .map(this::readUnchecked)
                .collect(Collectors.joining("\n"));
        }
    }

    private List<String> javaFileNames(Path sourceRoot) throws Exception {
        if (!Files.exists(sourceRoot)) {
            return List.of();
        }
        try (var paths = Files.walk(sourceRoot)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .map(path -> path.getFileName().toString())
                .toList();
        }
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }

    private List<String> presentTerms(String text, List<String> terms) {
        var normalized = text.toLowerCase(Locale.ROOT);
        return terms.stream()
            .filter(term -> normalized.contains(term.toLowerCase(Locale.ROOT)))
            .toList();
    }

    private void assertContainsAll(String text, String label, List<String> terms) {
        for (var term : terms) {
            assertThat(text).as(label + ": " + term).contains(term);
        }
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
                "api-key-123",
                "Authorization: Bearer"
            )
            .contains("[REDACTED]");
    }
}
