package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.agentpolicy.ToolContractRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V2Phase3AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final List<String> POST_V2_PHASE3_PACKAGES = List.of(
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
        "ManualRealLlm",
        "/orchestration/",
        "/report/",
        "/projectimport/"
    );

    @Test
    void phase3AcceptanceIsCoveredThroughControlledPlannerSeams() throws Exception {
        var plannerSources = controlledPlannerSourceText();
        var plannerTests = sourceText(PROJECT_ROOT.resolve("src/test/java/com/probeflow/testagent/controlledplanner"));
        var templates = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/llm/PromptTemplateRegistry.java"
        ));

        assertThat(plannerSources)
            .contains("enum PlannerAction")
            .contains("record PlanDecision")
            .contains("record PlannerInput")
            .contains("class FakeControlledPlanner")
            .contains("class ControlledPlannerService")
            .contains("class LlmBackedControlledPlanner")
            .contains("class PlanDecisionParser")
            .contains("WAIT_FOR_HUMAN")
            .contains("INSERT_STEP")
            .contains("REPLAN");
        assertThat(templates)
            .contains("v2.controlled-planner.v1")
            .contains("CONTROLLED_PLANNER");
        assertThat(plannerTests)
            .contains("PlanDecisionDomainModelTests")
            .contains("PlannerInputFactoryTests")
            .contains("FakeControlledPlannerTests")
            .contains("ControlledPlannerServiceTests")
            .contains("LlmBackedControlledPlannerTests")
            .contains("PlanDecisionParserTests")
            .contains("PlannerSafeBoundaryNonExecutionTests");
    }

    @Test
    void phase3DoesNotIntroducePolicyValidatorOrPlannerDecisionPersistence() throws Exception {
        var classNames = classNames(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/controlledplanner"));
        var plannerSources = controlledPlannerSourceText();
        var migrations = sourceText(PROJECT_ROOT.resolve("src/main/resources/db/migration"));

        assertThat(classNames).doesNotContain(
            "PolicyValidator",
            "PlanDecisionPolicyValidator",
            "PlannerPolicyValidator",
            "PlanDecisionRepository",
            "PlannerDecisionRepository",
            "PlanDecisionEntity",
            "PlannerDecisionEntity",
            "PlanDecisionJpaRepository",
            "PlannerDecisionJpaRepository"
        );
        assertThat(plannerSources)
            .doesNotContain("@Entity")
            .doesNotContain("JpaRepository")
            .doesNotContain("CrudRepository")
            .doesNotContain("Repository");
        assertThat(migrations.toLowerCase(Locale.ROOT))
            .doesNotContain("create table planner_decision")
            .doesNotContain("create table plan_decision")
            .doesNotContain("create index idx_planner_decision")
            .doesNotContain("create index idx_plan_decision")
            .doesNotContain("policy_validator");
    }

    @Test
    void phase3DoesNotIntroduceToolRouterExecutionOrChangeDefaultTaskOrchestrationFlow() throws Exception {
        var plannerSources = controlledPlannerSourceText();
        var orchestrationSource = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/orchestration/TaskOrchestrationApplicationService.java"
        ));

        assertThat(plannerSources)
            .doesNotContain("ToolRouter")
            .doesNotContain("ToolExecutor")
            .doesNotContain("ToolInvocation")
            .doesNotContain("PlanStepRunner")
            .doesNotContain("DefaultPlanStepRunner")
            .doesNotContain("TaskOrchestrationApplicationService")
            .doesNotContain("TaskRepository")
            .doesNotContain("PlanStepRepository")
            .doesNotContain(".save(")
            .doesNotContain(".delete(");
        assertThat(orchestrationSource)
            .doesNotContain("ControlledPlanner")
            .doesNotContain("PlanDecision")
            .doesNotContain("PlannerInput")
            .doesNotContain("LlmBackedControlledPlanner")
            .doesNotContain("FakeControlledPlanner");
    }

    @Test
    void phase3DoesNotIntroduceReplanningLoopHitlWorkflowMemoryFeedbackEvaluationRestFrontendQueueWorkerOrExternalIntegrations()
        throws Exception {
        var classNames = mainClassNames();
        var mainSources = mainSourceText();
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var controllerAnnotations = mainSources.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("@RestController") || line.startsWith("@Controller"))
            .toList();

        assertThat(classNames).doesNotContain(
            "ReplanningLoop",
            "PlanReplanningLoop",
            "HumanInTheLoopWorkflow",
            "HumanApprovalWorkflow",
            "AgentMemoryFeedbackLoop",
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
            "WebhookPublisher",
            "McpToolGateway",
            "PluginMarketplace"
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
            "mcp",
            "python",
            "pytest"
        ))).isEmpty();
    }

    @Test
    void registeredToolCatalogStillDoesNotContainExternalMcpPluginTicketCiOrUiTools() {
        var names = new ToolContractRegistry().listAll().stream()
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
                || name.startsWith("browser.")
                || name.startsWith("db.")
                || name.startsWith("service.")
        );
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
        return classNames(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent"), POST_V2_PHASE3_PACKAGES);
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

    private String controlledPlannerSourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/controlledplanner"));
    }

    private String mainSourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java"), POST_V2_PHASE3_PACKAGES);
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
