package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class Phase9AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final List<String> POST_PHASE9_PACKAGES = List.of(
        "/agentmemoryfeedback/",
        "/agentevaluation/",
        "/manualsuiteagent/",
        "/demorun/",
        "ManualRealLlm",
        "/projectimport/",
        "/contractsmoke/"
    );

    @Test
    void phase9AcceptanceIsCoveredThroughTaskOrchestrationApplicationServiceSeam() throws Exception {
        var phase9Sources = phase9SourceText();
        var orchestrationTests = phase9TestText();

        assertThat(phase9Sources)
            .contains("class TaskOrchestrationApplicationService")
            .contains("class TaskInitializationService")
            .contains("class TaskTemplateRegistry")
            .contains("class DefaultPlanStepRunner")
            .contains("interface PlanStepRunner")
            .contains("record StepOutcome")
            .contains("class ManualReviewGate")
            .contains("ApiAnalysisApplicationService")
            .contains("KnowledgeRetrievalApplicationService")
            .contains("TestCaseGenerationApplicationService")
            .contains("TestCasePromotionService")
            .contains("HttpExecutionApplicationService")
            .contains("FailureAnalysisApplicationService")
            .contains("ReportGenerationApplicationService");
        assertThat(orchestrationTests)
            .contains("initializesAutomaticApiTestTaskWithDeterministicPlanSteps")
            .contains("runsPendingPlanStepsInOrderAndCompletesTask")
            .contains("routesExecutionThroughHttpExecutionServiceUsingTaskMetadata")
            .contains("automaticApiTestPromotesGeneratedDraftsExecutesCasesSkipsAnalysisWhenAllPassedAndReports")
            .contains("manualApiTestPausesAfterCaseGenerationAndDoesNotExecuteWhileReviewPending")
            .contains("regressionInitializesExistingCasesExecutesAnalyzesFailuresAndReportsWithoutGeneration")
            .contains("missingExecutionReadinessInputsSkipHttpExecutionAndGeneratePartialReport")
            .contains("completedTaskResumeDoesNotDuplicateExecutionOrReportAndReturnsExistingReportId");
    }

    @Test
    void phase9DoesNotIntroduceRealLlmPlannerOrFreeFormAgentLoopToolSelection() throws Exception {
        var phase9Sources = phase9SourceText();
        var mainSources = mainSourceText();
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(phase9Sources)
            .doesNotContain("RealLlm")
            .doesNotContain("LlmPlanner")
            .doesNotContain("AiPlanner")
            .doesNotContain("PlannerAgent")
            .doesNotContain("PromptTemplate")
            .doesNotContain("ChatModel")
            .doesNotContain("ModelRouter")
            .doesNotContain("OpenAI")
            .doesNotContain("Anthropic")
            .doesNotContain("AgentLoop")
            .doesNotContain("ToolRegistry")
            .doesNotContain("ToolCall")
            .doesNotContain("selectTool")
            .doesNotContain("freeFormTool")
            .doesNotContain("FunctionCalling");
        assertThat(mainSources)
            .doesNotContain("RealLlm")
            .doesNotContain("LlmPlanner")
            .doesNotContain("AiPlanner")
            .doesNotContain("AgentLoop")
            .doesNotContain("ToolRegistry")
            .doesNotContain("ToolCall")
            .doesNotContain("OpenAI")
            .doesNotContain("Anthropic")
            .doesNotContain("ChatModel")
            .doesNotContain("ModelRouter");
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
    void phase9DoesNotIntroduceFrontendUiRestControllerLayerOrStaticRenderingAssets() throws Exception {
        var phase9Sources = phase9SourceText();
        var mainSources = mainSourceText();
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
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
            "itext"
        ))).isEmpty();
        assertThat(phase9Sources)
            .doesNotContain("TaskController")
            .doesNotContain("OrchestrationController")
            .doesNotContain("WorkflowController")
            .doesNotContain("Renderer")
            .doesNotContain("TemplateEngine")
            .doesNotContain("StaticResource")
            .doesNotContain("HtmlReport")
            .doesNotContain("PdfReport");
    }

    @Test
    void phase9DoesNotIntroduceQueuesWorkersExternalNotificationsTicketsOrSourceUploadSubsystems() throws Exception {
        var phase9Sources = phase9SourceText();
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(phase9Sources)
            .doesNotContain("@Async")
            .doesNotContain("@Scheduled")
            .doesNotContain("TaskQueue")
            .doesNotContain("JobQueue")
            .doesNotContain("QueueBacked")
            .doesNotContain("Worker")
            .doesNotContain("Kafka")
            .doesNotContain("Rabbit")
            .doesNotContain("Notification")
            .doesNotContain("Notifier")
            .doesNotContain("GitHubIssue")
            .doesNotContain("Jira")
            .doesNotContain("Slack")
            .doesNotContain("Email")
            .doesNotContain("Ticket")
            .doesNotContain("MultipartFile")
            .doesNotContain("FileUpload")
            .doesNotContain("ZipInputStream")
            .doesNotContain("unzip")
            .doesNotContain("cloneRepository")
            .doesNotContain("CloneCommand");
        assertThat(presentTerms(pom, List.of(
            "kafka",
            "rabbitmq",
            "quartz",
            "spring-cloud-stream",
            "hub4j",
            "jira",
            "slack",
            "sendgrid",
            "mailgun",
            "commons-compress",
            "zip4j",
            "jgit"
        ))).isEmpty();
    }

    @Test
    void phase9StaysWithinHttpApiTestingNoBrowserUiAutomationDirectInvocationOrDbAssertionEngine() throws Exception {
        var phase9Sources = phase9SourceText();
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(phase9Sources)
            .contains("HttpExecutionApplicationService")
            .contains("ExecutionMode")
            .contains("EXECUTE_BATCH")
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
            .doesNotContain("SqlAssertion")
            .doesNotContain("JdbcTemplate")
            .doesNotContain("DataSource");
        assertThat(presentTerms(pom, List.of(
            "playwright",
            "selenium",
            "cypress",
            "puppeteer",
            "wiremock",
            "mockwebserver",
            "okhttp",
            "httpclient5",
            "httpcomponents-client"
        ))).isEmpty();
    }

    @Test
    void phase9SourceShapeStaysFocusedOnDeterministicTemplateDrivenOrchestration() throws Exception {
        var classNames = Files.walk(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/orchestration"))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(path -> path.getFileName().toString().replace(".java", ""))
            .sorted()
            .toList();

        assertThat(classNames).containsExactly(
            "DefaultPlanStepRunner",
            "ManualReviewGate",
            "ManualReviewGateResult",
            "PlanStepRunner",
            "StepOutcome",
            "TaskInitializationRequest",
            "TaskInitializationResult",
            "TaskInitializationService",
            "TaskOrchestrationApplicationService",
            "TaskOrchestrationResult",
            "TaskPlanStepTemplate",
            "TaskTemplate",
            "TaskTemplateRegistry"
        );
        assertThat(classNames).doesNotContain(
            "LlmPlanner",
            "AgentLoopOrchestrator",
            "ToolRegistry",
            "TaskController",
            "TaskQueue",
            "TaskWorker",
            "NotificationPublisher",
            "ExternalTicketPublisher",
            "BrowserAutomationRunner",
            "DirectServiceInvocationRunner",
            "DbAssertionRunner",
            "SourceUploadService"
        );
    }

    private String phase9SourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/orchestration"));
    }

    private String phase9TestText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/test/java/com/probeflow/testagent/orchestration"));
    }

    private String mainSourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java"), POST_PHASE9_PACKAGES);
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
