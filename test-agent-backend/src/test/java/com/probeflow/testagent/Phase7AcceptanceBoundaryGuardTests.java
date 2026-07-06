package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class Phase7AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void phase7AcceptanceIsCoveredThroughFailureAnalysisApplicationServiceSeam() throws Exception {
        var phase7Sources = phase7SourceText();
        var failureAnalysisTests = Files.readString(PROJECT_ROOT.resolve(
            "src/test/java/com/probeflow/testagent/failureanalysis/FailureAnalysisApplicationServiceTests.java"
        ));

        assertThat(phase7Sources)
            .contains("class FailureAnalysisApplicationService")
            .contains("record FailureAnalysisResult")
            .contains("record TaskFailureAnalysisResult")
            .contains("enum FailureClassification")
            .contains("record SuiteFailureSummary")
            .contains("record MemoryCandidateAnalysisResult")
            .contains("ExecutionRecord")
            .contains("Observation")
            .contains("TaskMemoryService")
            .contains("AgentMemoryFeedbackApplicationService")
            .contains("refineFailureAnalysisCandidate");
        assertThat(failureAnalysisTests)
            .contains("singleExecutionAnalysisSummarizesPersistedExecutionFactsWithoutMutation")
            .contains("deterministicClassificationCoversAssertionTransportBlockedAndStatusEvidence")
            .contains("analysisPersistsMeaningfulObservationAndReusesItOnRepeatedAnalysis")
            .contains("retrySuggestionAndNextActionAreConservativeAndClassificationAware")
            .contains("suiteAnalysisExplainsFirstFailedStepAndDependentSkips")
            .contains("taskAnalysisAggregatesDeduplicatesOrdersAndFlagsMissingLinkedRecords")
            .contains("analysisWritesCompactTaskMemoryOnceForMeaningfulExecutionsAndSkipsNoisyPassedRuns")
            .contains("severeSingleExecutionProducesAcceptedLongTermMemoryCandidateThroughRefinery")
            .contains("repeatedTaskFailuresProduceMergedLongTermMemoryCandidateThroughExistingRefinery");
    }

    @Test
    void phase7DoesNotIntroduceFrontendUiStaticAssetsTemplatesOrReportRendering() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var phase7Sources = phase7SourceText();
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
        assertThat(phase7Sources)
            .doesNotContain("ReportRenderer")
            .doesNotContain("ReportRendering")
            .doesNotContain("renderReport")
            .doesNotContain("TemplateEngine")
            .doesNotContain("JasperReports");
    }

    @Test
    void phase7DoesNotIntroduceRealLlmPromptOrModelRoutingStacks() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var phase7Sources = phase7SourceText();
        var mainSources = mainSourceText();

        assertThat(phase7Sources)
            .doesNotContain("Prompt")
            .doesNotContain("prompt")
            .doesNotContain("ChatModel")
            .doesNotContain("ModelRouter")
            .doesNotContain("OpenAI")
            .doesNotContain("Anthropic")
            .doesNotContain("RealLlm")
            .doesNotContain("streaming");
        assertThat(mainSources)
            .doesNotContain("OpenAI")
            .doesNotContain("Anthropic")
            .doesNotContain("ChatModel")
            .doesNotContain("ModelRouter")
            .doesNotContain("RealLlm");
        assertThat(presentTerms(pom, List.of(
            "spring-ai",
            "langchain",
            "openai",
            "anthropic",
            "ollama",
            "bedrock"
        ))).isEmpty();
    }

    @Test
    void phase7DoesNotIntroduceAgentLoopAutoRerunTestCaseMutationOrExternalTickets() throws Exception {
        var phase7Sources = phase7SourceText();
        var mainSources = mainSourceText();
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(phase7Sources)
            .doesNotContain("AgentLoop")
            .doesNotContain("orchestrate")
            .doesNotContain("autoRerun")
            .doesNotContain("automaticRerun")
            .doesNotContain("rerunExecution")
            .doesNotContain("reexecute")
            .doesNotContain("TestCaseRepository.save")
            .doesNotContain("TestCaseMutation")
            .doesNotContain("GitHubIssue")
            .doesNotContain("Jira")
            .doesNotContain("Slack")
            .doesNotContain("Email")
            .doesNotContain("Ticket");
        assertThat(mainSources)
            .doesNotContain("AgentLoop")
            .doesNotContain("GitHubIssue")
            .doesNotContain("Jira")
            .doesNotContain("SlackClient")
            .doesNotContain("MailSender");
        assertThat(presentTerms(pom, List.of(
            "hub4j",
            "jira",
            "slack",
            "sendgrid",
            "mailgun"
        ))).isEmpty();
    }

    @Test
    void phase7DoesNotIntroduceBrowserAutomationDirectInvocationDbAssertionsQueuesOrLiveNetworkDependencies() throws Exception {
        var phase7Sources = phase7SourceText();
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(phase7Sources)
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
            .doesNotContain("Socket")
            .doesNotContain("@Async")
            .doesNotContain("@Scheduled")
            .doesNotContain("Queue")
            .doesNotContain("Kafka")
            .doesNotContain("Rabbit");
        assertThat(presentTerms(pom, List.of(
            "playwright",
            "selenium",
            "cypress",
            "puppeteer",
            "wiremock",
            "mockwebserver",
            "kafka",
            "rabbitmq",
            "quartz",
            "spring-cloud-stream"
        ))).isEmpty();
    }

    @Test
    void phase7SourceShapeStaysFocusedOnFailureAnalysisDtosAndApplicationService() throws Exception {
        var classNames = Files.walk(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/failureanalysis"))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(path -> path.getFileName().toString().replace(".java", ""))
            .sorted()
            .toList();

        assertThat(classNames).containsExactly(
            "FailedAssertionSummary",
            "FailureAnalysisApplicationService",
            "FailureAnalysisMode",
            "FailureAnalysisRequest",
            "FailureAnalysisResult",
            "FailureClassification",
            "GroupedFailureSummary",
            "MemoryCandidateAnalysisResult",
            "RequestFacts",
            "ResponseFacts",
            "SuiteFailureSummary",
            "TaskFailureAnalysisCounts",
            "TaskFailureAnalysisRequest",
            "TaskFailureAnalysisResult"
        );
        assertThat(classNames).doesNotContain(
            "FailureAnalysisController",
            "FailureReportRenderer",
            "FailureAnalysisPromptBuilder",
            "AgentLoopOrchestrator",
            "AutoRerunScheduler",
            "TestCaseMutationService",
            "ExternalTicketPublisher"
        );
    }

    private String phase7SourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/failureanalysis"));
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
