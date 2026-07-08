package com.probeflow.testagent.failureanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentArtifactReference;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentHarness;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunRequest;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunStatus;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSource;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V3Phase5AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @TempDir
    private Path outputDir;

    @Test
    void v3Phase5AcceptancePathsAreCoveredByApplicationAndHarnessRegressionTests() throws Exception {
        var applicationTests = Files.readString(PROJECT_ROOT.resolve(
            "src/test/java/com/probeflow/testagent/failureanalysis/FailureAnalysisApplicationServiceTests.java"
        ));
        var harnessTests = Files.readString(PROJECT_ROOT.resolve(
            "src/test/java/com/probeflow/testagent/manualsuiteagent/ManualSuiteAgentHarnessIssue07Tests.java"
        ));

        assertThat(applicationTests)
            .contains(
                "suiteAnalysisExplainsFirstFailedStepAndDependentSkips",
                "firstFailingStep",
                "rootCauseStep",
                "affectedDownstreamSteps",
                "impactSummary",
                "VARIABLE_EXTRACTION_FAILURE",
                "VARIABLE_RESOLUTION_FAILURE",
                "VARIABLE_WRITEBACK_FAILURE",
                "DEPENDENCY_ORDER_FAILURE",
                "BUSINESS_PRECONDITION_FAILURE",
                "DOWNSTREAM_API_FAILURE",
                "runtimeDiagnostic",
                "variableAuditEvent",
                "requestSnapshot",
                "responseSnapshot",
                "failedAssertions",
                "confidence",
                "requiresHumanReview",
                "riskLevel",
                "nextSuggestion",
                "recoveryActionType",
                "analysisPreparesPolicyGatedReplanningAndHumanHandoffsWithoutApplyingRecovery",
                "taskAnalysisAggregatesSuiteFailuresByRootCauseImpactAndKeepsObservationLifecycleIdempotent"
            );
        assertThat(harnessTests)
            .contains(
                "failureAnalysisExplainsVariableExtractionFailureFixture",
                "failureAnalysisExplainsVariableResolutionFailureFixture",
                "failureAnalysisExplainsPrerequisiteFailureSkippedDownstreamFixture",
                "failureAnalysisExplainsDownstreamApiFailureFixtureAndReportsConsistentArtifacts",
                "failure-analysis (REAL",
                "suiteFailureAnalysis",
                "assertNoSensitiveValues"
            );
    }

    @Test
    void failureAnalysisModuleStaysInsideV3Phase5AnalysisBoundaries() throws Exception {
        var source = sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/failureanalysis"));

        assertThat(presentTerms(source, List.of(
            "BusinessFlowCandidate",
            "BusinessFlowDiscoveryService",
            "SuiteDraftGenerationService",
            "SuiteDraftGenerationRequest",
            "SuiteExtractRule(",
            "SuiteVariableReference(",
            "ExecutionContext",
            "VariableResolver",
            "ResponseExtractor",
            "VariableWriteBackService",
            "com.probeflow.testagent.httpexecution",
            "HttpExecutionApplicationService",
            "HttpClient",
            "RestTemplate",
            "WebClient",
            "HttpURLConnection",
            "executeOrderSuite",
            "executeStep",
            "setRequestSnapshot(",
            "setResponseSnapshot(",
            "setAssertionResults(",
            "setOverallStatus(",
            "setSteps(",
            "setDetail(",
            "autoFix",
            "applyRecovery",
            "insertRecoveryStep",
            "rerunFailedStep"
        ))).isEmpty();
    }

    @Test
    void v3Phase5DoesNotIntroduceV3Phase6V4OrRealProviderSurfaces() throws Exception {
        var failureAnalysisSource = sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/failureanalysis"));
        var projectSource = sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent"));
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(presentTerms(failureAnalysisSource, List.of(
            "AgentEvaluationApplicationService",
            "LightConsole",
            "ConsoleController",
            "RagApplicationService",
            "VectorStore",
            "PgVector",
            "Mem0",
            "EmbeddingClient",
            "EmbeddingService",
            "OpenAI",
            "Anthropic",
            "Ollama",
            "Bedrock",
            "@RestController",
            "@Controller"
        ))).isEmpty();
        assertThat(presentTerms(projectSource, List.of(
            "mem0",
            "LightConsoleController"
        ))).isEmpty();
        assertThat(presentTerms(pom, List.of(
            "spring-ai",
            "langchain",
            "openai",
            "anthropic",
            "ollama",
            "bedrock",
            "mem0",
            "wiremock",
            "mockwebserver"
        ))).isEmpty();
    }

    @Test
    void manualHarnessFailureAnalysisIsRealDeterministicRedactedAndLeavesLaterSlotsUnfinished() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-downstream-api-failure", outputDir));

        assertThat(result.status()).isEqualTo(ManualSuiteAgentRunStatus.COMPLETED);
        assertThat(result.usesRealProvider()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(section(result.sections(), "failure-analysis"))
            .satisfies(section -> {
                assertThat(section.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
                assertThat(section.status()).isEqualTo("REVIEW");
                assertThat(section.summary())
                    .containsEntry("sourceMarker", "real")
                    .containsEntry("classification", "DOWNSTREAM_API_FAILURE")
                    .containsEntry("rootStep", "pay-order")
                    .containsEntry("recoveryActionType", "WAIT_FOR_SERVICE_OR_DATA_FIX");
                assertThat(section.summary()).containsKeys("suiteFailureAnalysis", "evidence", "nextSuggestion");
            });
        assertThat(section(result.sections(), "memory-feedback").source())
            .isEqualTo(ManualSuiteAgentSectionSource.STAGED);
        assertThat(section(result.sections(), "evaluation-comparison").source())
            .isEqualTo(ManualSuiteAgentSectionSource.NOT_RUN);

        var jsonText = Files.readString(artifactPath(result, "JSON_REPORT"));
        var markdown = Files.readString(artifactPath(result, "MARKDOWN_REPORT"));
        var json = new ObjectMapper().readTree(jsonText);
        assertThat(json.at("/run/usesRealLlm").asBoolean()).isFalse();
        assertThat(json.at("/run/usesExternalHttp").asBoolean()).isFalse();
        assertThat(json.toString()).contains("DOWNSTREAM_API_FAILURE", "suiteFailureAnalysis");
        assertThat(markdown)
            .contains("failure-analysis (REAL, REVIEW)")
            .contains("Classification: DOWNSTREAM_API_FAILURE")
            .contains("Root cause:")
            .contains("Next suggestion:");
        assertNoSensitiveValues(jsonText);
        assertNoSensitiveValues(markdown);
    }

    private ManualSuiteAgentSectionSummary section(List<ManualSuiteAgentSectionSummary> sections, String sectionId) {
        return sections.stream()
            .filter(section -> section.sectionId().equals(sectionId))
            .findFirst()
            .orElseThrow();
    }

    private Path artifactPath(com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunResult result, String artifactType) {
        return result.artifacts().stream()
            .filter(artifact -> artifact.artifactType().equals(artifactType))
            .findFirst()
            .map(ManualSuiteAgentArtifactReference::path)
            .map(Path::of)
            .orElseThrow();
    }

    private String sourceText(Path sourceRoot) throws Exception {
        try (var paths = Files.walk(sourceRoot)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .map(this::readUnchecked)
                .collect(Collectors.joining("\n"));
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
