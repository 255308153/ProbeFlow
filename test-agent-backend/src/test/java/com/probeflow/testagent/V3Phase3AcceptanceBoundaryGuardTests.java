package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentArtifactReference;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentHarness;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentProviderMode;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunRequest;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunStatus;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSource;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V3Phase3AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @TempDir
    private Path outputDir;

    @Test
    void v3Phase3FocusedTestsCoverAllDependencyLinkerAcceptancePaths() throws Exception {
        var requiredTests = new LinkedHashMap<String, String>();
        requiredTests.put(
            "contract and minimal orderId path",
            "src/test/java/com/probeflow/testagent/suitedraft/SuiteDraftGenerationIssue01Tests.java"
        );
        requiredTests.put(
            "paired extractRules and variable references",
            "src/test/java/com/probeflow/testagent/suitedraft/SuiteDraftGenerationIssue02Tests.java"
        );
        requiredTests.put(
            "requestTemplate rewrite and metadata snapshot",
            "src/test/java/com/probeflow/testagent/suitedraft/SuiteDraftGenerationIssue03Tests.java"
        );
        requiredTests.put(
            "header status code and token dependency variants",
            "src/test/java/com/probeflow/testagent/suitedraft/SuiteDraftGenerationIssue04Tests.java"
        );
        requiredTests.put(
            "readiness validation dependency correctness",
            "src/test/java/com/probeflow/testagent/suitedraft/SuiteDraftGenerationIssue05Tests.java"
        );
        requiredTests.put(
            "confidence blockers and human-review gating",
            "src/test/java/com/probeflow/testagent/suitedraft/SuiteDraftGenerationIssue06Tests.java"
        );
        requiredTests.put(
            "manual suite harness generated-suite-draft integration",
            "src/test/java/com/probeflow/testagent/suitedraft/SuiteDraftGenerationIssue07HarnessIntegrationTests.java"
        );

        for (var entry : requiredTests.entrySet()) {
            var testPath = PROJECT_ROOT.resolve(entry.getValue());
            assertThat(testPath)
                .as(entry.getKey())
                .exists()
                .isRegularFile();
            assertThat(Files.readString(testPath))
                .as(entry.getKey())
                .contains("@Test")
                .contains("SuiteDraftGeneration");
        }

        var suiteDraftTests = sourceText(PROJECT_ROOT.resolve("src/test/java/com/probeflow/testagent/suitedraft"));
        assertThat(suiteDraftTests)
            .contains("minimalOrderIdPathGeneratesStableSuiteDraftWithoutExternalSideEffects")
            .contains("sameDependencyLinkGeneratesProducerExtractRuleAndConsumerReferences")
            .contains("requestTemplateRewriteCoversPathQueryBodyAndHeaderWithoutFlatteningStructure")
            .contains("suiteStepSnapshotsIncludeMetadataAndDoNotOnlyReferenceSingleCaseIds")
            .contains("SuiteDependencySourceType.HEADER")
            .contains("SuiteDependencySourceType.STATUS_CODE")
            .contains("lowConfidenceDependencyCannotBecomeReady")
            .contains("manualRealLlmModeIsBlockedUnlessExplicitlyAllowed")
            .contains("generatedSuiteDraftIsConsistentAcrossJsonMarkdownAndStableAcrossRuns")
            .contains("assertNoSensitiveValues");
    }

    @Test
    void suitedraftPackageStaysGenerationSideOnlyAndDoesNotImplementLaterPhaseRuntimeSurfaces() throws Exception {
        var suiteDraftSourceRoot = PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/suitedraft");
        var suiteDraftSources = sourceText(suiteDraftSourceRoot);
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(javaFileNames(suiteDraftSourceRoot))
            .noneMatch(name -> name.contains("ExecutionContext"))
            .noneMatch(name -> name.contains("ContextStore"))
            .noneMatch(name -> name.contains("VariableResolver"))
            .noneMatch(name -> name.contains("DynamicValueProvider"))
            .noneMatch(name -> name.contains("ResponseExtractor"))
            .noneMatch(name -> name.contains("VariableWriteBack"))
            .noneMatch(name -> name.contains("HttpExecution"))
            .noneMatch(name -> name.contains("FailureAnalysis"))
            .noneMatch(name -> name.contains("LightConsole"));

        assertThat(presentTerms(suiteDraftSources, List.of(
            "ExecutionContext",
            "ContextStore",
            "VariableResolver",
            "DynamicValueProvider",
            "ResponseExtractor",
            "VariableWriteBackService",
            "VariableWriteBack",
            "RuntimeVariableAudit",
            "auditEvents",
            "java.net.http.HttpClient",
            "HttpURLConnection",
            "RestTemplate",
            "WebClient",
            "OkHttpClient",
            "com.probeflow.testagent.failureanalysis",
            "com.probeflow.testagent.replanning",
            "com.probeflow.testagent.humanintheloop",
            "com.probeflow.testagent.agentmemoryfeedback",
            "com.probeflow.testagent.agentevaluation",
            "FailureClassifier",
            "SuiteFailureAnalysis",
            "MemoryFeedback",
            "AgentEvaluationHarness",
            "LightConsole",
            "EmbeddingService",
            "EmbeddingClient",
            "pgvector",
            "BM25",
            "QueryRewrite",
            "Small-to-Big",
            "SmallToBig",
            "mem0"
        ))).isEmpty();
        assertThat(presentTerms(pom, List.of(
            "spring-ai",
            "langchain",
            "openai",
            "anthropic",
            "ollama",
            "bedrock",
            "selenium",
            "playwright",
            "cypress",
            "wiremock",
            "mockwebserver"
        ))).isEmpty();
    }

    @Test
    void defaultHarnessRunShowsRealSuiteDraftAndV3Phase4RuntimeVariableAudit() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        assertThat(result.status()).isEqualTo(ManualSuiteAgentRunStatus.COMPLETED);
        assertThat(result.providerMode()).isEqualTo(ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE);
        assertThat(result.usesRealProvider()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();

        var businessFlowDiscovery = section(result, "business-flow-discovery");
        assertThat(businessFlowDiscovery.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(businessFlowDiscovery.summary().toString())
            .contains("Create order -> Pay order -> Query order")
            .contains("confidence")
            .contains("evidence");

        var generatedSuiteDraft = section(result, "generated-suite-draft");
        assertThat(generatedSuiteDraft.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(generatedSuiteDraft.status()).isEqualTo("READY");
        assertThat(generatedSuiteDraft.summary())
            .containsEntry("flowId", "flow-create-order-pay-order-query-order")
            .containsEntry("readinessStatus", "READY")
            .containsEntry("sourceMarker", "real");
        assertThat(generatedSuiteDraft.summary().toString())
            .contains("rule-create-order-orderId")
            .contains("$.data.orderId")
            .contains("pay-order")
            .contains("query-order")
            .contains("${suite.orderId}");

        var variableAudit = section(result, "variable-audit");
        assertThat(variableAudit.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(variableAudit.status()).isEqualTo("PASSED");
        assertThat(variableAudit.summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("runtime", "ExecutionContext");

        assertThat(result.sections())
            .extracting(ManualSuiteAgentSectionSummary::sectionId)
            .doesNotContain(
                "execution-context",
                "variable-resolver",
                "response-extractor",
                "variable-writeback-service",
                "suite-failure-analysis",
                "replanning",
                "human-in-the-loop",
                "v3-memory-feedback",
                "v3-agent-evaluation",
                "v3-light-console"
            );
    }

    @Test
    void defaultHarnessArtifactsUseFakeProvidersNoExternalHttpAndRedactSensitiveValues() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));
        var json = Files.readString(artifactPath(result, "JSON_REPORT"));
        var markdown = Files.readString(artifactPath(result, "MARKDOWN_REPORT"));
        var parsed = new ObjectMapper().readTree(json);

        assertThat(parsed.at("/run/usesRealLlm").asBoolean()).isFalse();
        assertThat(parsed.at("/run/usesExternalHttp").asBoolean()).isFalse();
        assertThat(parsed.at("/run/providerMode").asText()).isEqualTo("DETERMINISTIC_FAKE");
        assertThat(parsed.at("/metadata/executionSummary/gateway").asText()).isEqualTo("FAKE_HTTP");
        assertThat(parsed.toString())
            .contains("generated-suite-draft")
            .contains("rule-create-order-orderId")
            .contains("${suite.orderId}");
        assertThat(markdown)
            .contains("generated-suite-draft (REAL, READY)")
            .contains("rule-create-order-orderId")
            .contains("${suite.orderId}")
            .contains("variable-audit (REAL, PASSED)")
            .contains("Runtime: ExecutionContext");

        assertNoSensitiveValues(json);
        assertNoSensitiveValues(markdown);
    }

    private ManualSuiteAgentSectionSummary section(
        com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunResult result,
        String sectionId
    ) {
        return result.sections().stream()
            .filter(section -> section.sectionId().equals(sectionId))
            .findFirst()
            .orElseThrow();
    }

    private Path artifactPath(
        com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunResult result,
        String artifactType
    ) {
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
