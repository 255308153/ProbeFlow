package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.AgentPolicy;
import com.probeflow.testagent.agentpolicy.AgentPolicyService;
import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import com.probeflow.testagent.agentpolicy.ToolPolicyReasonCode;
import com.probeflow.testagent.agentpolicy.ToolPolicyStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V2Phase4AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final List<String> POST_V2_PHASE4_PACKAGES = List.of(
        "/humanintheloop/",
        "/replanning/",
        "/httpexecution/",
        "/suiteruntime/",
        "/failureanalysis/",
        "/agentmemoryfeedback/",
        "/agentevaluation/",
        "/manualsuiteagent/",
        "/demorun/",
        "/orchestration/",
        "/report/"
    );

    @Test
    void phase4AcceptanceIsCoveredThroughPolicyValidatorServiceSeams() throws Exception {
        var policyValidatorSources = policyValidatorSourceText();
        var policyValidatorTests = sourceText(PROJECT_ROOT.resolve("src/test/java/com/probeflow/testagent/policyvalidator"));

        assertThat(policyValidatorSources)
            .contains("enum PolicyValidationStatus")
            .contains("enum PolicyValidationReasonCode")
            .contains("record PolicyValidationRequest")
            .contains("record PolicyValidationResult")
            .contains("class PolicyValidatorService")
            .contains("ALLOWED")
            .contains("REQUIRES_HUMAN_CONFIRMATION")
            .contains("BLOCKED")
            .contains("V1_BOUNDARY_BLOCKED")
            .contains("MISSING_FAILURE_SIGNAL");
        assertThat(policyValidatorTests)
            .contains("PolicyValidationResultContractTests")
            .contains("PolicyValidatorDecisionSafetyTests")
            .contains("PolicyValidatorToolPolicyValidationTests")
            .contains("PolicyValidatorHumanConfirmationGateTests")
            .contains("PolicyValidatorV1BoundaryTests")
            .contains("PolicyValidatorTaskOrderPreconditionTests")
            .contains("PolicyValidatorServiceEndToEndCompositionTests");
    }

    @Test
    void policyValidatorDoesNotIntroduceToolRouterExecutionAgentLoopOrTaskOrchestrationIntegration() throws Exception {
        var policyValidatorSources = policyValidatorSourceText();
        var orchestrationSource = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/orchestration/TaskOrchestrationApplicationService.java"
        ));

        assertThat(policyValidatorSources)
            .doesNotContain("ToolRouter")
            .doesNotContain("ToolExecutor")
            .doesNotContain("ToolInvocation")
            .doesNotContain("PlanStepRunner")
            .doesNotContain("DefaultPlanStepRunner")
            .doesNotContain("TaskOrchestrationApplicationService")
            .doesNotContain("TaskRepository")
            .doesNotContain("PlanStepRepository")
            .doesNotContain("TestCaseRepository")
            .doesNotContain("ExecutionRecordRepository")
            .doesNotContain("ObservationRepository")
            .doesNotContain("EntityManager")
            .doesNotContain("DataSource")
            .doesNotContain(".save(")
            .doesNotContain(".delete(");
        assertThat(orchestrationSource)
            .doesNotContain("PolicyValidatorService")
            .doesNotContain("PolicyValidationRequest")
            .doesNotContain("PolicyValidationResult")
            .doesNotContain("PolicyValidationStatus");
    }

    @Test
    void phase4DoesNotImplementReplanningLoopHumanWorkflowEvaluationRestFrontendQueueWorkerOrExternalIntegrations()
        throws Exception {
        var mainSources = mainSourceText();
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var classNames = mainClassNames();
        var controllerAnnotations = mainSources.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("@RestController") || line.startsWith("@Controller"))
            .toList();

        assertThat(classNames).doesNotContain(
            "ToolRouter",
            "ToolExecutor",
            "ToolInvocation",
            "ReplanningLoop",
            "PlanReplanningLoop",
            "AgentLoop",
            "AgentLoopRunner",
            "HumanInTheLoopWorkflow",
            "HumanApprovalWorkflow",
            "UserApprovalService",
            "TeamApprovalService",
            "PermissionService",
            "ApprovalFlowService",
            "AgentEvaluationService",
            "AgentEvaluator",
            "AgentEvaluationHarness",
            "ToolQueue",
            "ToolWorker",
            "AgentWorker",
            "ExternalTicketPublisher",
            "GithubTicketPublisher",
            "JiraTicketPublisher",
            "SlackNotificationPublisher",
            "EmailNotificationPublisher",
            "WebhookPublisher",
            "CiPipelineRunner",
            "BrowserAutomationRunner",
            "UiAutomationRunner"
        );
        assertThat(controllerAnnotations).isEmpty();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
        assertThat(mainSources)
            .doesNotContain("@Async")
            .doesNotContain("@Scheduled")
            .doesNotContain("GitHubIssue")
            .doesNotContain("JiraClient")
            .doesNotContain("SlackClient")
            .doesNotContain("MailSender")
            .doesNotContain("WebhookClient")
            .doesNotContain("WebDriver")
            .doesNotContain("Playwright")
            .doesNotContain("Selenium")
            .doesNotContain("ProcessBuilder")
            .doesNotContain("pytest");
        assertThat(presentTerms(pom, List.of(
            "react",
            "vite",
            "nextjs",
            "kafka",
            "rabbitmq",
            "spring-cloud-stream",
            "hub4j",
            "jira",
            "slack",
            "sendgrid",
            "mailgun",
            "mcp",
            "python",
            "pytest",
            "playwright",
            "selenium"
        ))).isEmpty();
    }

    @Test
    void registeredToolsStillStayWithinBackendApiTestingBoundaryAndForbiddenCategoriesAreBlocked() {
        var registry = new ToolContractRegistry();
        var policyService = new AgentPolicyService(registry);
        var names = registry.listAll().stream()
            .map(contract -> contract.name().value())
            .toList();

        assertThat(names).contains(
            "api.analyze-source",
            "knowledge.retrieve-context",
            "memory.build-context",
            "testcase.generate-drafts",
            "testcase.review-draft",
            "http.execute-approved-case",
            "failure.analyze-execution",
            "report.generate-task"
        );
        assertThat(names).noneMatch(name ->
            name.startsWith("github.")
                || name.startsWith("jira.")
                || name.startsWith("slack.")
                || name.startsWith("webhook.")
                || name.startsWith("ticket.")
                || name.startsWith("mcp.")
                || name.startsWith("plugin.")
                || name.startsWith("ci.")
                || name.startsWith("ui.")
                || name.startsWith("browser.")
                || name.startsWith("db.")
                || name.startsWith("service.")
        );

        for (var toolName : List.of(
            "ui.run-automation",
            "browser.run-playwright",
            "service.direct-call",
            "db.direct-assertion",
            "notify.external-message",
            "github.create-issue",
            "jira.create-ticket",
            "slack.post-message",
            "webhook.send-event",
            "ci.run-pipeline",
            "mcp.invoke-tool",
            "plugin.install-marketplace"
        )) {
            var decision = policyService.evaluate(toolName, AgentPolicy.v2Phase2Default());

            assertThat(decision.status()).as(toolName).isEqualTo(ToolPolicyStatus.BLOCKED);
            assertThat(decision.reasonCode()).as(toolName).isEqualTo(ToolPolicyReasonCode.V1_BOUNDARY_BLOCKED);
        }
    }

    @Test
    void realLlmStillIsNotRequiredForCi() throws Exception {
        var testConfig = Files.readString(PROJECT_ROOT.resolve("src/test/resources/application-test.yml"));
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var mainSources = mainSourceText();

        assertThat(testConfig)
            .contains("allow-real-providers: false")
            .contains("allowed-providers: fake");
        assertThat(mainSources)
            .doesNotContain("OpenAI")
            .doesNotContain("Anthropic")
            .doesNotContain("RealLlm")
            .doesNotContain("ChatModel")
            .doesNotContain("java.net.http.HttpClient")
            .doesNotContain("HttpURLConnection");
        assertThat(presentTerms(pom, List.of(
            "spring-ai",
            "langchain",
            "openai",
            "anthropic",
            "ollama",
            "bedrock"
        ))).isEmpty();
    }

    private List<String> mainClassNames() throws Exception {
        return classNames(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent"), POST_V2_PHASE4_PACKAGES);
    }

    private List<String> classNames(Path sourceRoot) throws Exception {
        return classNames(sourceRoot, List.of());
    }

    private List<String> classNames(Path sourceRoot, List<String> excludedPathSegments) throws Exception {
        try (var stream = Files.walk(sourceRoot)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> excludedPathSegments.stream().noneMatch(path.toString()::contains))
                .map(path -> path.getFileName().toString().replace(".java", ""))
                .sorted()
                .toList();
        }
    }

    private String policyValidatorSourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/policyvalidator"));
    }

    private String mainSourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java"), POST_V2_PHASE4_PACKAGES);
    }

    private String sourceText(Path sourceRoot) throws Exception {
        return sourceText(sourceRoot, List.of());
    }

    private String sourceText(Path sourceRoot, List<String> excludedPathSegments) throws Exception {
        try (var stream = Files.walk(sourceRoot)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".sql"))
                .filter(path -> excludedPathSegments.stream().noneMatch(path.toString()::contains))
                .map(this::readUnchecked)
                .collect(Collectors.joining("\n"));
        }
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
