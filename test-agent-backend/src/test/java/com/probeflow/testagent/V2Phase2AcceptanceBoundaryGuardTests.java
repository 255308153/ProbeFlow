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

class V2Phase2AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final List<String> POST_V2_PHASE2_PACKAGES = List.of(
        "/controlledplanner/",
        "/policyvalidator/",
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

    private final ToolContractRegistry registry = new ToolContractRegistry();
    private final AgentPolicyService policyService = new AgentPolicyService(registry);

    @Test
    void v2Phase2AcceptanceIsCoveredThroughToolContractRegistryAndAgentPolicySeams() throws Exception {
        var agentPolicySources = agentPolicySourceText();
        var agentPolicyTests = sourceText(PROJECT_ROOT.resolve("src/test/java/com/probeflow/testagent/agentpolicy"));

        assertThat(agentPolicySources)
            .contains("record ToolName")
            .contains("record ToolContract")
            .contains("class ToolContractRegistry")
            .contains("record AgentPolicy")
            .contains("class AgentPolicyService")
            .contains("record ToolPolicyDecision")
            .contains("class PlannerSafeToolCatalogService");
        assertThat(agentPolicyTests)
            .contains("ToolContractDomainModelTests")
            .contains("ToolContractRegistryTests")
            .contains("AgentPolicyServiceTests")
            .contains("ToolSchemaPreconditionValidationTests")
            .contains("HighRiskV1BoundaryPolicyTests")
            .contains("PlannerSafeToolCatalogServiceTests");
    }

    @Test
    void phase2DoesNotIntroduceControlledPlannerStructuredPlannerParsingToolRouterOrReplanning() throws Exception {
        var agentPolicySources = agentPolicySourceText();
        var agentPolicyClassNames = classNames(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/agentpolicy"));

        assertThat(agentPolicyClassNames).doesNotContain(
            "ControlledPlanner",
            "LlmPlanner",
            "PlannerDecision",
            "PlanDecision",
            "PlannerAction",
            "PlannerOutputParser",
            "ToolRouter",
            "ToolExecutor",
            "ToolInvocation",
            "ReplanningLoop",
            "PolicyValidator"
        );
        assertThat(agentPolicySources)
            .doesNotContain("ControlledPlanner")
            .doesNotContain("PlannerDecision")
            .doesNotContain("PlanDecision")
            .doesNotContain("PlannerAction")
            .doesNotContain("structured planner output")
            .doesNotContain("ToolRouter")
            .doesNotContain("ToolExecutor")
            .doesNotContain("ToolInvocation")
            .doesNotContain("ReplanningLoop");
    }

    @Test
    void phase2DoesNotIntroduceHumanInLoopWorkflowAgentEvaluationRestFrontendQueueWorkerOrExternalIntegrations() throws Exception {
        var mainSources = mainSourceText();
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var controllerAnnotations = mainSources.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("@RestController") || line.startsWith("@Controller"))
            .toList();
        var classNames = mainClassNames();

        assertThat(controllerAnnotations).isEmpty();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
        assertThat(classNames).doesNotContain(
            "HumanInTheLoopWorkflow",
            "HumanApprovalWorkflow",
            "AgentEvaluationService",
            "AgentEvaluator",
            "ToolQueue",
            "ToolWorker",
            "AgentWorker",
            "ExternalTicketPublisher",
            "GithubTicketPublisher",
            "JiraTicketPublisher",
            "SlackNotificationPublisher"
        );
        assertThat(mainSources)
            .doesNotContain("@Async")
            .doesNotContain("@Scheduled")
            .doesNotContain("GitHubIssue")
            .doesNotContain("Jira")
            .doesNotContain("SlackClient")
            .doesNotContain("MailSender")
            .doesNotContain("WebhookClient")
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
            "python",
            "pytest"
        ))).isEmpty();
    }

    @Test
    void noExternalMcpPluginTicketCiOrNotificationToolsAreRegistered() {
        var names = registry.listAll().stream()
            .map(contract -> contract.name().value())
            .toList();

        assertThat(names).contains(
            "api.analyze-source",
            "knowledge.retrieve-context",
            "memory.build-context",
            "testcase.generate-drafts",
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
                || name.startsWith("db.")
                || name.startsWith("service.")
        );
    }

    @Test
    void v1BoundaryPolicyBlocksExternalAndUnsafeToolRequests() {
        for (var toolName : List.of(
            "ui.browser-automation",
            "service.direct-call",
            "db.direct-assertion",
            "ticket.create-github",
            "jira.create-ticket",
            "slack.post-message",
            "webhook.send-event",
            "ci.run-pipeline",
            "mcp.invoke-tool",
            "plugin.install-marketplace"
        )) {
            var decision = policyService.evaluate(toolName, AgentPolicy.v2Phase2Default());

            assertThat(decision.status()).isEqualTo(ToolPolicyStatus.BLOCKED);
            assertThat(decision.reasonCode()).isEqualTo(ToolPolicyReasonCode.V1_BOUNDARY_BLOCKED);
        }
    }

    @Test
    void plannerSafeViewsDoNotExposeJavaServiceRepositoryDatabaseHttpClientOrSpringBeanInstances() throws Exception {
        var agentPolicySources = agentPolicySourceText();
        var agentPolicyTests = sourceText(PROJECT_ROOT.resolve("src/test/java/com/probeflow/testagent/agentpolicy"));

        assertThat(agentPolicySources)
            .doesNotContain("ApplicationService")
            .doesNotContain("Repository")
            .doesNotContain("EntityManager")
            .doesNotContain("DataSource")
            .doesNotContain("HttpClient")
            .doesNotContain("WebClient")
            .doesNotContain("RestTemplate")
            .doesNotContain("ToolRouter")
            .doesNotContain("PlanStepRunner");
        assertThat(agentPolicyTests).contains("plannerSafeViewDoesNotExposeImplementationDetailsOrServiceReferences");
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
        return classNames(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent"));
    }

    private List<String> classNames(Path sourceRoot) throws Exception {
        return Files.walk(sourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(path -> path.getFileName().toString().replace(".java", ""))
            .sorted()
            .toList();
    }

    private String agentPolicySourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/agentpolicy"));
    }

    private String mainSourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java"), POST_V2_PHASE2_PACKAGES);
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
