package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V2Phase5AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void phase5IntegrationStaysLimitedToExplicitOrchestrationRecoveryPoints() throws Exception {
        var orchestrationSource = sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/orchestration"));
        var replanningSource = sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/replanning"));
        var phase5Tests = sourceText(PROJECT_ROOT.resolve("src/test/java/com/probeflow/testagent/orchestration"));

        assertThat(orchestrationSource)
            .contains("class TaskOrchestrationApplicationService")
            .contains("PlanStepRunner")
            .contains("ManualReviewGate")
            .contains("ReplanningApplicationService")
            .contains("replanningEnabled")
            .contains("PLAN_STEP_FAILED")
            .contains("EXECUTION_READINESS_MISSING")
            .doesNotContain("ToolRouter")
            .doesNotContain("ToolRegistry")
            .doesNotContain("AgentLoop")
            .doesNotContain("AutoGPT");
        assertThat(replanningSource)
            .contains("PolicyValidatorService")
            .contains("ControlledPlannerService")
            .contains("ReplanningTrigger")
            .contains("MAX_TASK_REPLANNING_ATTEMPTS")
            .contains("MAX_TRIGGER_REPLANNING_ATTEMPTS");
        assertThat(phase5Tests)
            .contains("defaultTemplateFailureDoesNotEnterReplanningLoop")
            .contains("explicitFailureReplanningTruncatesDownstreamAndAppendsAuditedRecoveryStep")
            .contains("explicitReadinessReplanningPausesForHumanWithoutRunningDownstream");
    }

    @Test
    void phase5DoesNotAddRestUiQueueWorkerMultiAgentOrExternalIntegrationSurface() throws Exception {
        var mainSources = mainSourceText();
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var controllerAnnotations = mainSources.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("@RestController") || line.startsWith("@Controller"))
            .toList();

        assertThat(controllerAnnotations).isEmpty();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
        assertThat(presentTerms(mainSources, List.of(
            "WebConsole",
            "ReviewController",
            "HumanReviewPage",
            "BackgroundDaemon",
            "TaskQueue",
            "JobQueue",
            "QueueBacked",
            "TaskWorker",
            "ReplanningWorker",
            "MultiAgent",
            "AgentCoordinator",
            "SlackClient",
            "SlackNotifier",
            "JiraClient",
            "JiraTicket",
            "GitHubIssue",
            "WebhookPublisher",
            "WebhookClient",
            "EmailNotifier",
            "CiTrigger"
        ))).isEmpty();
        assertThat(presentTerms(pom, List.of(
            "kafka",
            "rabbitmq",
            "quartz",
            "spring-cloud-stream",
            "hub4j",
            "jira",
            "slack",
            "sendgrid",
            "mailgun"
        ))).isEmpty();
    }

    @Test
    void phase5DoesNotAddUiAutomationOrRealLlmCiDependency() throws Exception {
        var mainSources = mainSourceText();
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(presentTerms(mainSources, List.of(
            "BrowserAutomation",
            "UiAutomation",
            "WebDriver",
            "Playwright",
            "Selenium",
            "Cypress",
            "Puppeteer"
        ))).isEmpty();
        assertThat(presentTerms(pom, List.of(
            "spring-ai",
            "langchain",
            "openai",
            "anthropic",
            "ollama",
            "bedrock",
            "playwright",
            "selenium",
            "cypress",
            "puppeteer"
        ))).isEmpty();
    }

    private String mainSourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java"));
    }

    private String sourceText(Path sourceRoot) throws Exception {
        return Files.walk(sourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .filter(path -> !path.toString().contains("/demorun/"))
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
