package com.probeflow.testagent.manualsuiteagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualSuiteAgentHarnessIssue06Tests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @TempDir
    private Path outputDir;

    @Test
    void defaultHarnessRunStaysFakeOnlyAndKeepsLaterV3CapabilitiesAsSlots() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        assertThat(result.status()).isEqualTo(ManualSuiteAgentRunStatus.COMPLETED);
        assertThat(result.providerMode()).isEqualTo(ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE);
        assertThat(result.usesRealProvider()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(result.metadata().get("executionSummary").toString()).contains("FAKE_HTTP");

        assertThat(section(result, "generated-suite-draft").source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(section(result, "business-flow-discovery").source()).isEqualTo(ManualSuiteAgentSectionSource.REAL);
        assertThat(section(result, "variable-audit").source()).isEqualTo(ManualSuiteAgentSectionSource.PENDING_RUNTIME);
        assertThat(section(result, "failure-analysis").source()).isEqualTo(ManualSuiteAgentSectionSource.STAGED);
        assertThat(section(result, "memory-feedback").source()).isEqualTo(ManualSuiteAgentSectionSource.STAGED);
        assertThat(section(result, "evaluation-comparison").source()).isEqualTo(ManualSuiteAgentSectionSource.NOT_RUN);
        assertThat(result.sections())
            .extracting(ManualSuiteAgentSectionSummary::sectionId)
            .doesNotContain(
                "dependency-linker",
                "execution-context",
                "variable-resolver",
                "response-extractor",
                "variable-writeback-service",
                "replanning",
                "human-in-the-loop",
                "v3-memory-feedback",
                "v3-agent-evaluation",
                "v3-light-console"
            );

        var json = new ObjectMapper().readTree(Files.readString(artifactPath(result, "JSON_REPORT")));
        assertThat(json.at("/run/usesRealLlm").asBoolean()).isFalse();
        assertThat(json.at("/run/usesExternalHttp").asBoolean()).isFalse();
        assertThat(json.at("/metadata/executionSummary/gateway").asText()).isEqualTo("FAKE_HTTP");

        var testConfig = Files.readString(PROJECT_ROOT.resolve("src/test/resources/application-test.yml"));
        assertThat(testConfig)
            .contains("allow-real-providers: false")
            .contains("allowed-providers: fake");
    }

    @Test
    void v3Phase1DoesNotIntroduceConsoleControllerAutomationQueueDbDirectOrExternalProviderSurfaces() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var manualSources = sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/manualsuiteagent"));
        var mainSources = sourceText(PROJECT_ROOT.resolve("src/main/java"));
        var controllerAnnotations = mainSources.lines()
            .map(String::trim)
            .filter(line -> line.equals("@RestController") || line.equals("@Controller"))
            .toList();

        assertThat(controllerAnnotations).isEmpty();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
        assertThat(javaFileNames(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/manualsuiteagent")))
            .noneMatch(name -> name.contains("Controller"))
            .noneMatch(name -> name.contains("Console"))
            .noneMatch(name -> name.contains("Discovery"))
            .noneMatch(name -> name.contains("DependencyLinker"))
            .noneMatch(name -> name.contains("ExecutionContext"))
            .noneMatch(name -> name.contains("VariableResolver"))
            .noneMatch(name -> name.contains("ResponseExtractor"))
            .noneMatch(name -> name.contains("VariableWriteBack"))
            .noneMatch(name -> name.contains("LightConsole"));

        assertThat(presentTerms(manualSources, List.of(
            "@RestController",
            "@Controller",
            "@RequestMapping",
            "ApplicationService",
            "invokeService",
            "ServiceInvoker",
            "directInvocation",
            "DbAssertion",
            "DatabaseAssertion",
            "JdbcTemplate",
            "EntityManager",
            "DataSource",
            "Repository",
            "WebDriver",
            "Playwright",
            "Selenium",
            "Cypress",
            "Puppeteer",
            "Kafka",
            "Rabbit",
            "JmsTemplate",
            "MessageQueue",
            "WebSocket",
            "Grpc",
            "WebClient",
            "RestTemplate",
            "java.net.http.HttpClient",
            "HttpURLConnection",
            "OpenAI",
            "Anthropic",
            "EmbeddingClient",
            "EmbeddingService",
            "LlmApplicationService",
            "com.probeflow.testagent.failureanalysis",
            "com.probeflow.testagent.replanning",
            "com.probeflow.testagent.humanintheloop",
            "com.probeflow.testagent.agentmemoryfeedback",
            "com.probeflow.testagent.agentevaluation"
        ))).isEmpty();
        assertThat(presentTerms(pom, List.of(
            "react",
            "vite",
            "nextjs",
            "thymeleaf",
            "freemarker",
            "spring-security",
            "slack",
            "jira",
            "hub4j",
            "sendgrid",
            "mailgun",
            "jakarta.mail",
            "kafka",
            "rabbitmq",
            "spring-cloud-stream",
            "spring-ai",
            "langchain",
            "openai",
            "anthropic",
            "ollama",
            "bedrock",
            "wiremock",
            "mockwebserver",
            "selenium",
            "playwright",
            "cypress",
            "puppeteer"
        ))).isEmpty();
    }

    @Test
    void reportsDiagnosticsAndArtifactMetadataDoNotExposeSensitiveFields() throws Exception {
        var invalidFixture = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("v3-invalid-fixture", outputDir));
        var json = Files.readString(artifactPath(invalidFixture, "JSON_REPORT"));
        var markdown = Files.readString(artifactPath(invalidFixture, "MARKDOWN_REPORT"));
        var diagnosticText = invalidFixture.diagnostics().toString();

        assertNoSensitiveValues(json);
        assertNoSensitiveValues(markdown);
        assertNoSensitiveValues(diagnosticText);
        assertThat(diagnosticText).contains("[REDACTED]");

        var diagnostic = ManualSuiteAgentDiagnostic.error(
            "BOUNDARY_REDACTION_SAMPLE",
            "Authorization: Bearer sample-secret-token",
            Map.of(
                "token", "sample-secret-token",
                "nested", Map.of("apiKey", "sample-api-key", "cookie", "sample-cookie")
            )
        );
        var artifact = new ManualSuiteAgentArtifactReference(
            "JSON_REPORT",
            "/tmp/manual-suite-agent-report.json",
            "application/json",
            Map.of("apiKey", "sample-api-key", "credential", Map.of("password", "sample-password"))
        );

        assertNoSensitiveValues(diagnostic.toString());
        assertNoSensitiveValues(artifact.metadata().toString());
        assertThat(diagnostic.toString()).contains("[REDACTED]");
        assertThat(artifact.metadata().toString()).contains("[REDACTED]");
    }

    @Test
    void v3Phase1CriticalPathsHaveFocusedTests() throws Exception {
        var requiredTests = Map.of(
            "harness contract fixture registry smoke run",
            "src/test/java/com/probeflow/testagent/manualsuiteagent/ManualSuiteAgentHarnessIssue01Tests.java",
            "order suite fake HTTP execution",
            "src/test/java/com/probeflow/testagent/manualsuiteagent/ManualSuiteAgentHarnessIssue02Tests.java",
            "staged V3 sections",
            "src/test/java/com/probeflow/testagent/manualsuiteagent/ManualSuiteAgentHarnessIssue03Tests.java",
            "provider safety diagnostics redaction",
            "src/test/java/com/probeflow/testagent/manualsuiteagent/ManualSuiteAgentHarnessIssue04Tests.java",
            "local run command artifact output",
            "src/test/java/com/probeflow/testagent/manualsuiteagent/ManualSuiteAgentHarnessIssue05Tests.java"
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
                .contains("ManualSuiteAgentHarness");
        }
    }

    private ManualSuiteAgentSectionSummary section(ManualSuiteAgentRunResult result, String sectionId) {
        return result.sections().stream()
            .filter(section -> section.sectionId().equals(sectionId))
            .findFirst()
            .orElseThrow();
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
                "api-key-123",
                "sample-secret-token",
                "sample-api-key",
                "sample-cookie",
                "sample-password"
            );
    }

    private String sourceText(Path sourceRoot) throws Exception {
        return Files.walk(sourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(this::readUnchecked)
            .collect(Collectors.joining("\n"));
    }

    private List<String> javaFileNames(Path sourceRoot) throws Exception {
        return Files.walk(sourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(path -> path.getFileName().toString())
            .toList();
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
}
