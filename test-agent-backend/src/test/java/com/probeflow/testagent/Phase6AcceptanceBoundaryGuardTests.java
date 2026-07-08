package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class Phase6AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final List<String> POST_PHASE6_PACKAGES = List.of(
        "/failureanalysis/",
        "/agentmemoryfeedback/",
        "/agentevaluation/",
        "/manualsuiteagent/",
        "/demorun/",
        "ManualRealLlm",
        "/orchestration/",
        "/report/"
    );

    @Test
    void phase6AcceptanceIsCoveredThroughHttpExecutionApplicationServiceSeam() throws Exception {
        var phase6Sources = phase6SourceText();
        var executionTests = Files.readString(PROJECT_ROOT.resolve(
            "src/test/java/com/probeflow/testagent/httpexecution/HttpExecutionApplicationServiceTests.java"
        ));

        assertThat(phase6Sources)
            .contains("class HttpExecutionApplicationService")
            .contains("interface HttpClientGateway")
            .contains("class ExecutableRequestBuilder")
            .contains("class HttpResponseSnapshotFactory")
            .contains("class BaselineHttpAssertionChecker")
            .contains("TaskMemoryService")
            .contains("ExecutionRecord")
            .contains("TaskCaseExecution");
        assertThat(executionTests)
            .contains("singleExecutionThroughFakeHttpClientPersistsRecordAndTaskCaseExecution")
            .contains("dryRunResolvesEnvironmentAndAuthPlaceholdersRedactsSecretsAndSkipsTransport")
            .contains("executionUsesApiSpecFallbackRouteAndAuthMetadataWhenRequestShapeOmitsThem")
            .contains("successfulResponseSnapshotCapturesHeadersBodyMetadataAndTruncatesLargeText")
            .contains("failedExpectedStatusAssertionFailsExecutionRecord")
            .contains("batchExecutesSelectedCasesInDeterministicOrderAndAggregatesCounts")
            .contains("suiteExecutesOrderedStepsAndPersistsStepDetails")
            .contains("blockedHostPolicyBlocksBeforeTransportWithClassification")
            .contains("requestSnapshotPreservesExplicitRedirectAndRetryPolicyMetadata")
            .contains("failedExecutionWritesTaskMemoryAndLeavesTestCaseDefinitionUntouched");
    }

    @Test
    void phase6DoesNotIntroduceFrontendUiOrReportRenderingSurfaces() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var phase6Sources = phase6SourceText();
        var mainSources = mainSourceText();
        var controllerAnnotations = mainSources.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("@RestController") || line.startsWith("@Controller"))
            .toList();

        assertThat(controllerAnnotations).isEmpty();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
        assertThat(presentTerms(pom, List.of(
            "react",
            "vite",
            "nextjs",
            "thymeleaf",
            "freemarker",
            "jasperreports"
        ))).isEmpty();
        assertThat(phase6Sources)
            .doesNotContain("ReportRenderer")
            .doesNotContain("ReportRendering")
            .doesNotContain("renderReport")
            .doesNotContain("TemplateEngine")
            .doesNotContain("JasperReports");
    }

    @Test
    void phase6KeepsExecutionBoundaryHttpOnlyWithoutAutomationDirectInvocationDbAssertionsOrRealLlm() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var phase6Sources = phase6SourceText();
        var mainSources = mainSourceText();

        assertThat(phase6Sources)
            .doesNotContain("BrowserAutomation")
            .doesNotContain("UiAutomation")
            .doesNotContain("WebDriver")
            .doesNotContain("Playwright")
            .doesNotContain("Selenium")
            .doesNotContain("Cypress")
            .doesNotContain("Puppeteer")
            .doesNotContain("invokeService")
            .doesNotContain("directInvocation")
            .doesNotContain("ServiceInvoker")
            .doesNotContain("DbAssertion")
            .doesNotContain("DatabaseAssertion")
            .doesNotContain("JdbcTemplate")
            .doesNotContain("EntityManager")
            .doesNotContain("DataSource");
        assertThat(presentTerms(pom, List.of(
            "playwright",
            "selenium",
            "cypress",
            "puppeteer",
            "spring-ai",
            "langchain",
            "openai",
            "anthropic",
            "ollama",
            "bedrock"
        ))).isEmpty();
        assertThat(mainSources)
            .doesNotContain("OpenAI")
            .doesNotContain("Anthropic")
            .doesNotContain("ChatModel")
            .doesNotContain("ModelRouter")
            .doesNotContain("RealLlm");
    }

    @Test
    void phase6CiUsesFakeHttpClientAndHasNoMandatoryLiveNetworkDependency() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var phase6Sources = phase6SourceText();
        var fakeClient = Files.readString(PROJECT_ROOT.resolve(
            "src/test/java/com/probeflow/testagent/httpexecution/FakeHttpClientGateway.java"
        ));
        var executionTests = Files.readString(PROJECT_ROOT.resolve(
            "src/test/java/com/probeflow/testagent/httpexecution/HttpExecutionApplicationServiceTests.java"
        ));

        assertThat(phase6Sources)
            .contains("class NoopHttpClientGateway")
            .contains("interface HttpClientGateway")
            .doesNotContain("WebClient")
            .doesNotContain("RestTemplate")
            .doesNotContain("java.net.http.HttpClient")
            .doesNotContain("HttpURLConnection")
            .doesNotContain("openConnection")
            .doesNotContain("Socket");
        assertThat(fakeClient)
            .contains("class FakeHttpClientGateway implements HttpClientGateway")
            .contains("respondWith")
            .contains("respondWithSequence")
            .contains("failWith")
            .contains("requests.add");
        assertThat(executionTests)
            .contains("@Primary")
            .contains("FakeHttpClientGateway")
            .contains("fakeHttpClient.requests()")
            .doesNotContain("WireMock")
            .doesNotContain("MockWebServer");
        assertThat(presentTerms(pom, List.of(
            "wiremock",
            "mockwebserver",
            "junit-pioneer-http",
            "okhttp",
            "httpclient5",
            "httpcomponents-client"
        ))).isEmpty();
    }

    private String phase6SourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/httpexecution"));
    }

    private String mainSourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java"), POST_PHASE6_PACKAGES);
    }

    private String sourceText(Path sourceRoot) throws Exception {
        return sourceText(sourceRoot, List.of());
    }

    private String sourceText(Path sourceRoot, List<String> excludedPathSegments) throws Exception {
        return Files.walk(sourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .filter(path -> excludedPathSegments.stream().noneMatch(path.toString()::contains))
            .map(this::readUnchecked)
            .collect(Collectors.joining("\n"));
    }

    private List<String> presentTerms(String text, List<String> terms) {
        var normalized = text.toLowerCase(Locale.ROOT);
        return terms.stream()
            .filter(term -> normalized.contains(term.toLowerCase(Locale.ROOT)))
            .toList();
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
