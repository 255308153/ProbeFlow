package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V2Phase1AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void v2Phase1AcceptanceIsCoveredThroughLlmApplicationServiceSeams() throws Exception {
        var llmSources = llmSourceText();
        var llmTests = sourceText(PROJECT_ROOT.resolve("src/test/java/com/probeflow/testagent/llm"));

        assertThat(llmSources)
            .contains("interface LlmProvider")
            .contains("class FakeLlmProvider")
            .contains("class PromptTemplateRegistry")
            .contains("class LlmCallLog")
            .contains("interface LlmCallLogRepository")
            .contains("class LlmApplicationService")
            .contains("record LlmExecutionOptions")
            .contains("class LlmPolicy")
            .contains("class LlmAuditSanitizer");
        assertThat(llmTests)
            .contains("LlmProviderContractTests")
            .contains("FakeLlmProviderTests")
            .contains("PromptTemplateRegistryTests")
            .contains("LlmApplicationServiceTests")
            .contains("LlmPolicyTests")
            .contains("successfulCallRendersTemplateInvokesProviderAndPersistsSuccessLog")
            .contains("policyBlockedCallDoesNotAccessProviderAndPersistsAuditLog")
            .contains("auditLogRedactsApiKeysAndSensitiveHeadersFromPromptAndResponseSummaries")
            .contains("requestHashIsStableForEquivalentRequests");
    }

    @Test
    void llmModuleDoesNotIntroduceControlledPlannerAgentLoopOrToolCalling() throws Exception {
        var llmSources = llmSourceText();
        var classNames = llmClassNames();

        assertThat(classNames).doesNotContain(
            "ControlledPlanner",
            "LlmPlanner",
            "AiPlanner",
            "PlannerAgent",
            "AgentLoop",
            "AgentLoopOrchestrator",
            "ToolRouter",
            "ToolRegistry",
            "ToolCall",
            "ToolInvocation",
            "PlanStepRunner"
        );
        assertThat(llmSources)
            .doesNotContain("ControlledPlanner")
            .doesNotContain("LlmPlanner")
            .doesNotContain("AiPlanner")
            .doesNotContain("PlannerAgent")
            .doesNotContain("AgentLoop")
            .doesNotContain("ToolRouter")
            .doesNotContain("ToolRegistry")
            .doesNotContain("ToolCall")
            .doesNotContain("selectTool")
            .doesNotContain("FunctionCalling")
            .doesNotContain("PlanStepRunner");
    }

    @Test
    void llmModuleDoesNotCallHttpExecutorOrBusinessWritePathsDirectly() throws Exception {
        var llmSources = llmSourceText();

        assertThat(llmSources)
            .doesNotContain("HttpExecutionApplicationService")
            .doesNotContain("httpexecution")
            .doesNotContain("TestCaseRepository")
            .doesNotContain("TestCaseDraftRepository")
            .doesNotContain("ExecutionRecordRepository")
            .doesNotContain("ObservationRepository")
            .doesNotContain("TaskMemoryService")
            .doesNotContain("LongTermMemoryRepository")
            .doesNotContain("MemoryRefineryService")
            .doesNotContain("ReportGenerationApplicationService")
            .doesNotContain("ReportRepository")
            .doesNotContain("saveTestCase")
            .doesNotContain("writeTaskMemory")
            .doesNotContain("generateReport");
    }

    @Test
    void ciDoesNotRequireRealLlmApiKeySdkOrNetworkClient() throws Exception {
        var llmSources = llmSourceText();
        var testConfig = Files.readString(PROJECT_ROOT.resolve("src/test/resources/application-test.yml"));
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(testConfig)
            .contains("allow-real-providers: false")
            .contains("allowed-providers: fake");
        assertThat(llmSources)
            .doesNotContain("OpenAI")
            .doesNotContain("Anthropic")
            .doesNotContain("ChatModel")
            .doesNotContain("ModelRouter")
            .doesNotContain("RealLlm")
            .doesNotContain("API_KEY")
            .doesNotContain("WebClient")
            .doesNotContain("RestTemplate")
            .doesNotContain("java.net.http.HttpClient")
            .doesNotContain("HttpURLConnection")
            .doesNotContain("openConnection")
            .doesNotContain("Socket");
        assertThat(presentTerms(pom, List.of(
            "spring-ai",
            "langchain",
            "openai",
            "anthropic",
            "ollama",
            "bedrock",
            "okhttp",
            "httpclient5",
            "httpcomponents-client"
        ))).isEmpty();
    }

    @Test
    void v2Phase1DoesNotIntroduceFrontendRestControllersQueuesWorkersExternalTicketsOrPythonRunner() throws Exception {
        var mainSources = mainSourceText();
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var controllerAnnotations = mainSources.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("@RestController") || line.startsWith("@Controller"))
            .toList();
        var classNames = Files.walk(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent"))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(path -> path.getFileName().toString().replace(".java", ""))
            .sorted()
            .toList();

        assertThat(controllerAnnotations).isEmpty();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
        assertThat(classNames)
            .doesNotContain("LlmController")
            .doesNotContain("PromptController")
            .doesNotContain("LlmQueue")
            .doesNotContain("LlmWorker")
            .doesNotContain("LlmNotificationPublisher")
            .doesNotContain("ExternalTicketPublisher")
            .doesNotContain("GithubTicketPublisher")
            .doesNotContain("JiraTicketPublisher")
            .doesNotContain("SlackNotificationPublisher")
            .doesNotContain("PytestRunner")
            .doesNotContain("PythonRunner");
        assertThat(mainSources)
            .doesNotContain("@Async")
            .doesNotContain("@Scheduled")
            .doesNotContain("GitHubIssue")
            .doesNotContain("Jira")
            .doesNotContain("SlackClient")
            .doesNotContain("MailSender")
            .doesNotContain("pytest")
            .doesNotContain("ProcessBuilder");
        assertThat(presentTerms(pom, List.of(
            "react",
            "vite",
            "nextjs",
            "thymeleaf",
            "freemarker",
            "kafka",
            "rabbitmq",
            "quartz",
            "spring-cloud-stream",
            "hub4j",
            "jira",
            "slack",
            "sendgrid",
            "mailgun",
            "python",
            "pytest"
        ))).isEmpty();
    }

    private List<String> llmClassNames() throws Exception {
        return Files.walk(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/llm"))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(path -> path.getFileName().toString().replace(".java", ""))
            .sorted()
            .toList();
    }

    private String llmSourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/llm"));
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
