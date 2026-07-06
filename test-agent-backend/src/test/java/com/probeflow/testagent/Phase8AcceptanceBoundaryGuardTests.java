package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class Phase8AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void phase8AcceptanceIsCoveredThroughReportGenerationApplicationServiceSeam() throws Exception {
        var phase8Sources = phase8SourceText();
        var reportTests = Files.readString(PROJECT_ROOT.resolve(
            "src/test/java/com/probeflow/testagent/report/ReportGenerationApplicationServiceTests.java"
        ));

        assertThat(phase8Sources)
            .contains("class ReportGenerationApplicationService")
            .contains("record ReportGenerationRequest")
            .contains("record ReportGenerationResult")
            .contains("interface ReportRepository")
            .contains("class Report")
            .contains("FailureAnalysisApplicationService")
            .contains("ExecutionRecord")
            .contains("Observation")
            .contains("TaskMemoryService")
            .contains("LongTermMemoryRepository")
            .contains("phase8.v1")
            .contains("HTTP_API_TESTING");
        assertThat(reportTests)
            .contains("generatesPersistedNoCasesReportSnapshotForEmptyTask")
            .contains("reportsNoExecutionsWhenTaskHasLinkedCasesButNoExecutionRecords")
            .contains("basicSnapshotUsesExecutionEnvironmentAndDoesNotMutateExecutionState")
            .contains("aggregatesMixedExecutionOutcomesCoverageDurationAndModes")
            .contains("generatesStructuredFindingsFromExecutionFactsObservationsAndMissingLinks")
            .contains("buildsDeduplicatedPrioritizedMachineReadableSuggestions")
            .contains("reusesExistingBasicObservationsAndSkipsIneligiblePassedExecutions")
            .contains("triggersMissingBasicAnalysisOnceForEligibleExecutionWithoutDeepOrLlmDependency")
            .contains("suiteAndMixedExecutionReportHighlightsFirstFailingStepAndDependentSkips")
            .contains("memoryFeedbackSummarizesTaskMemoriesAcceptedCandidatesAndRejectedNotes")
            .contains("repeatedGenerationCreatesImmutableEquivalentSnapshotsForIdenticalInputs")
            .contains("regenerationAfterNewExecutionCreatesNewSnapshotAndPreservesOlderReport");
    }

    @Test
    void phase8DoesNotIntroduceFrontendUiRestControllersStaticAssetsTemplatesOrRenderers() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var phase8Sources = phase8SourceText();
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
            "jasperreports",
            "openhtmltopdf",
            "itext",
            "flying-saucer",
            "commonmark"
        ))).isEmpty();
        assertThat(phase8Sources)
            .doesNotContain("ReportController")
            .doesNotContain("ReportRenderer")
            .doesNotContain("ReportRendering")
            .doesNotContain("ReportExporter")
            .doesNotContain("PdfReport")
            .doesNotContain("HtmlReport")
            .doesNotContain("MarkdownReport")
            .doesNotContain("renderReport")
            .doesNotContain("exportReport")
            .doesNotContain("TemplateEngine")
            .doesNotContain("JasperReports");
    }

    @Test
    void phase8DoesNotIntroduceRealLlmAgentLoopNotificationsExternalTicketsOrAutoRerun() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var phase8Sources = phase8SourceText();
        var mainSources = mainSourceText();

        assertThat(phase8Sources)
            .doesNotContain("Prompt")
            .doesNotContain("prompt")
            .doesNotContain("ChatModel")
            .doesNotContain("ModelRouter")
            .doesNotContain("OpenAI")
            .doesNotContain("Anthropic")
            .doesNotContain("RealLlm")
            .doesNotContain("streaming")
            .doesNotContain("AgentLoop")
            .doesNotContain("orchestrate")
            .doesNotContain("autoRerun")
            .doesNotContain("automaticRerun")
            .doesNotContain("rerunExecution")
            .doesNotContain("reexecute")
            .doesNotContain("Notification")
            .doesNotContain("Notifier")
            .doesNotContain("GitHubIssue")
            .doesNotContain("Jira")
            .doesNotContain("Slack")
            .doesNotContain("Email")
            .doesNotContain("Ticket");
        assertThat(mainSources)
            .doesNotContain("OpenAI")
            .doesNotContain("Anthropic")
            .doesNotContain("ChatModel")
            .doesNotContain("ModelRouter")
            .doesNotContain("RealLlm")
            .doesNotContain("AgentLoop")
            .doesNotContain("GitHubIssue")
            .doesNotContain("Jira")
            .doesNotContain("SlackClient")
            .doesNotContain("MailSender");
        assertThat(presentTerms(pom, List.of(
            "spring-ai",
            "langchain",
            "openai",
            "anthropic",
            "ollama",
            "bedrock",
            "hub4j",
            "jira",
            "slack",
            "sendgrid",
            "mailgun"
        ))).isEmpty();
    }

    @Test
    void phase8DoesNotIntroduceBrowserAutomationDirectInvocationDbAssertionsQueuesWorkersOrLiveNetworkDependencies() throws Exception {
        var phase8Sources = phase8SourceText();
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(phase8Sources)
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
            .doesNotContain("DataSource")
            .doesNotContain("WebClient")
            .doesNotContain("RestTemplate")
            .doesNotContain("java.net.http.HttpClient")
            .doesNotContain("HttpURLConnection")
            .doesNotContain("openConnection")
            .doesNotContain("Socket")
            .doesNotContain("@Async")
            .doesNotContain("@Scheduled")
            .doesNotContain("Queue")
            .doesNotContain("Worker")
            .doesNotContain("Kafka")
            .doesNotContain("Rabbit");
        assertThat(presentTerms(pom, List.of(
            "playwright",
            "selenium",
            "cypress",
            "puppeteer",
            "wiremock",
            "mockwebserver",
            "okhttp",
            "httpclient5",
            "httpcomponents-client",
            "kafka",
            "rabbitmq",
            "quartz",
            "spring-cloud-stream"
        ))).isEmpty();
    }

    @Test
    void phase8SourceShapeStaysFocusedOnStructuredTaskReportDataOnly() throws Exception {
        var classNames = Files.walk(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/report"))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(path -> path.getFileName().toString().replace(".java", ""))
            .sorted()
            .toList();

        assertThat(classNames).containsExactly(
            "Report",
            "ReportGenerationApplicationService",
            "ReportGenerationRequest",
            "ReportGenerationResult",
            "ReportRepository"
        );
        assertThat(classNames).doesNotContain(
            "ReportController",
            "ReportRenderer",
            "ReportExporter",
            "PdfReportRenderer",
            "HtmlReportRenderer",
            "MarkdownReportRenderer",
            "ReportPromptBuilder",
            "AgentLoopReportPlanner",
            "ReportNotificationPublisher",
            "ExternalTicketPublisher",
            "ReportJobWorker",
            "ReportQueue"
        );
    }

    private String phase8SourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/report"));
    }

    private String mainSourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java"));
    }

    private String sourceText(Path sourceRoot) throws Exception {
        return Files.walk(sourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
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
