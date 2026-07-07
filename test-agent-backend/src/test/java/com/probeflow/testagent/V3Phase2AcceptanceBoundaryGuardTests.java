package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentHarness;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunRequest;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V3Phase2AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @TempDir
    private Path outputDir;

    @Test
    void orderSuiteDemoContainsRealBusinessFlowDiscoveryAndSuiteDraftButNoRuntimeVariableOutput() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        var discovery = result.sections().stream()
            .filter(section -> section.sectionId().equals("business-flow-discovery"))
            .findFirst()
            .orElseThrow();
        assertThat(discovery.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(discovery.summary().toString())
            .contains("Create order -> Pay order -> Query order")
            .contains("confidence")
            .contains("evidence")
            .contains("blockers")
            .contains("requiresHumanReview")
            .contains("sourceCoverage");

        var generatedSuiteDraft = result.sections().stream()
            .filter(section -> section.sectionId().equals("generated-suite-draft"))
            .findFirst()
            .orElseThrow();
        assertThat(generatedSuiteDraft.source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(generatedSuiteDraft.summary().toString())
            .contains("${suite.orderId}")
            .contains("extractRules")
            .contains("V3-3 DependencyLinker");

        var variableAudit = result.sections().stream()
            .filter(section -> section.sectionId().equals("variable-audit"))
            .findFirst()
            .orElseThrow();
        assertThat(variableAudit.source()).isEqualTo(ManualSuiteAgentSectionSource.PENDING_RUNTIME);
        assertThat(variableAudit.status()).isEqualTo("PENDING_RUNTIME");

        assertThat(result.sections())
            .extracting(section -> section.sectionId())
            .doesNotContain(
                "dependency-linker",
                "execution-context",
                "variable-resolver",
                "response-extractor",
                "variable-writeback-service"
            );
    }

    @Test
    void v3Phase2DoesNotIntroduceOutOfScopeSurfacesOrRequiredExternalDependencies() throws Exception {
        var discoverySources = sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/businessflowdiscovery"));
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(discoverySources).doesNotContain("@RestController", "@Controller");
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
        assertThat(presentTerms(discoverySources, List.of(
            "DependencyLinker",
            "ExecutionContext",
            "VariableResolver",
            "ResponseExtractor",
            "VariableWriteBack",
            "extractRules",
            "${suite.",
            "${step.",
            "HttpClient",
            "RestTemplate",
            "WebClient",
            "HttpURLConnection",
            "EmbeddingService",
            "LlmApplicationService",
            "OpenAI",
            "Anthropic",
            "Selenium",
            "Playwright",
            "Kafka",
            "Rabbit",
            "JdbcTemplate",
            "EntityManager"
        ))).isEmpty();
        assertThat(presentTerms(pom, List.of(
            "spring-ai",
            "langchain",
            "openai",
            "anthropic",
            "ollama",
            "selenium",
            "playwright",
            "cypress",
            "wiremock",
            "mockwebserver",
            "kafka",
            "rabbitmq"
        ))).isEmpty();
    }

    @Test
    void harnessReportsRedactSensitiveValuesAfterBusinessFlowDiscoveryIntegration() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));
        var json = Files.readString(artifactPath(result, "JSON_REPORT"));
        var markdown = Files.readString(artifactPath(result, "MARKDOWN_REPORT"));

        assertNoSensitiveValues(json);
        assertNoSensitiveValues(markdown);
        var parsed = new ObjectMapper().readTree(json);
        assertThat(parsed.get("sections").toString()).contains("business-flow-discovery");
    }

    @Test
    void v3Phase2FocusedTestsCoverAllIssues() {
        for (var testFile : List.of(
            "src/test/java/com/probeflow/testagent/businessflowdiscovery/BusinessFlowDiscoveryIssue01Tests.java",
            "src/test/java/com/probeflow/testagent/businessflowdiscovery/BusinessFlowDiscoveryIssue02Tests.java",
            "src/test/java/com/probeflow/testagent/businessflowdiscovery/BusinessFlowDiscoveryIssue03Tests.java",
            "src/test/java/com/probeflow/testagent/businessflowdiscovery/BusinessFlowDiscoveryIssue04Tests.java",
            "src/test/java/com/probeflow/testagent/businessflowdiscovery/BusinessFlowDiscoveryIssue05Tests.java",
            "src/test/java/com/probeflow/testagent/businessflowdiscovery/BusinessFlowDiscoveryIssue06Tests.java",
            "src/test/java/com/probeflow/testagent/businessflowdiscovery/BusinessFlowDiscoveryIssue07HarnessReportTests.java"
        )) {
            assertThat(PROJECT_ROOT.resolve(testFile)).exists().isRegularFile();
        }
    }

    private String sourceText(Path sourceRoot) throws Exception {
        if (!Files.exists(sourceRoot)) {
            return "";
        }
        var builder = new StringBuilder();
        try (var paths = Files.walk(sourceRoot)) {
            for (var path : paths.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .toList()) {
                builder.append(Files.readString(path)).append('\n');
            }
        }
        return builder.toString();
    }

    private List<String> presentTerms(String text, List<String> terms) {
        var normalized = text.toLowerCase(Locale.ROOT);
        return terms.stream()
            .filter(term -> normalized.contains(term.toLowerCase(Locale.ROOT)))
            .toList();
    }

    private Path artifactPath(
        com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunResult result,
        String artifactType
    ) {
        return result.artifacts().stream()
            .filter(artifact -> artifact.artifactType().equals(artifactType))
            .findFirst()
            .map(artifact -> Path.of(artifact.path()))
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
