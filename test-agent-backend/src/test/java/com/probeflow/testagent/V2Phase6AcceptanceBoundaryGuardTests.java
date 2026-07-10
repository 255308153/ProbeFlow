package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V2Phase6AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void phase6DoesNotAddRestControllersWebConsoleFrontendOrExternalApprovalSurfaces() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var mainSources = mainSourceText();
        var phase6Sources = phase6SourceText();
        var controllerAnnotations = mainSources.lines()
            .map(String::trim)
            .filter(line -> line.equals("@RestController") || line.equals("@Controller"))
            .toList();

        assertThat(controllerAnnotations).isEmpty();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
        assertThat(presentTerms(mainSources, List.of(
            "WebConsole",
            "ReviewController",
            "HumanReviewController",
            "HumanReviewPage",
            "ReviewDashboard",
            "Frontend"
        ))).isEmpty();
        assertThat(presentTerms(pom, List.of(
            "react",
            "vite",
            "nextjs",
            "thymeleaf",
            "freemarker",
            "spring-security",
            "hub4j",
            "jira",
            "slack",
            "sendgrid",
            "mailgun",
            "javax.mail",
            "jakarta.mail"
        ))).isEmpty();
        assertThat(presentTerms(phase6Sources, List.of(
            "PreAuthorize",
            "PostAuthorize",
            "Secured",
            "RolesAllowed",
            "PermissionEvaluator",
            "AccessDecisionVoter",
            "ApprovalChain",
            "ApprovalQuorum",
            "ApprovalVote",
            "ApproverGroup",
            "MultiUser",
            "quorum",
            "secondApprover",
            "dualControl",
            "SlackClient",
            "SlackNotifier",
            "JiraClient",
            "JiraTicket",
            "GitHubIssue",
            "WebhookPublisher",
            "WebhookClient",
            "EmailNotifier",
            "MailSender",
            "TicketClient"
        ))).isEmpty();
    }

    @Test
    void phase6DoesNotAddBackgroundNotificationDistributedWorkerOrRealLlmCiDependency() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var phase6Sources = phase6SourceText();

        assertThat(presentTerms(phase6Sources, List.of(
            "@Scheduled",
            "@Async",
            "TaskScheduler",
            "NotificationService",
            "NotificationPublisher",
            "BackgroundNotifier",
            "Kafka",
            "Rabbit",
            "MessageQueue",
            "DistributedWorker",
            "HumanReviewWorker",
            "ReplanningWorker",
            "JobQueue",
            "QueueBacked",
            "WebClient",
            "RestTemplate",
            "java.net.http.HttpClient",
            "HttpURLConnection",
            "OpenAI",
            "Anthropic",
            "LlmApplicationService",
            "LlmCallRequest",
            "LlmProvider"
        ))).isEmpty();
        assertThat(presentTerms(pom, List.of(
            "kafka",
            "rabbitmq",
            "quartz",
            "spring-cloud-stream",
            "spring-ai",
            "langchain",
            "openai",
            "anthropic",
            "ollama",
            "bedrock",
            "wiremock",
            "mockwebserver"
        ))).isEmpty();
    }

    @Test
    void humanInputStaysValidatedBeforePlannerOrDatabaseMutationAndCouplingRemainsApplicationLayer() throws Exception {
        var humanService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/humanintheloop/HumanInTheLoopApplicationService.java"
        ));
        var replanningService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/replanning/ReplanningApplicationService.java"
        ));
        var humanEntities = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/humanintheloop/HumanReviewRequest.java"
        )) + "\n" + Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/humanintheloop/HumanDecisionRecord.java"
        ));

        assertThat(humanService)
            .contains("validateDraftReviewDecision")
            .contains("validateBlockerResolutionDecision")
            .contains("validateHighRiskDecision")
            .contains("validatePlannerClarificationDecision")
            .contains("submitDecision")
            .contains("validatePayload")
            .contains("validateField")
            .contains("sanitizeMap")
            .contains("MASKED_VALUE")
            .contains("applyPlannerClarificationDecision")
            .doesNotContain("ControlledPlannerService")
            .doesNotContain("PlannerInput")
            .doesNotContain("LlmApplicationService")
            .doesNotContain("LlmCallRequest");
        assertThat(replanningService)
            .contains("HumanInTheLoopApplicationService")
            .contains("policyValidator.validate")
            .contains("controlledPlanner.plan(plannerInput)")
            .contains("humanInputConstraints(request.humanInput())")
            .doesNotContain("HumanReviewRequestRepository")
            .doesNotContain("HumanDecisionRecordRepository");
        assertThat(humanEntities)
            .doesNotContain("com.probeflow.testagent.replanning")
            .doesNotContain("ControlledPlanner")
            .doesNotContain("PolicyValidator")
            .doesNotContain("Llm");
    }

    @Test
    void v1ManualReviewGateAndDefaultOrchestrationFlowRemainOwnedByOrchestrationLayer() throws Exception {
        var manualReviewGate = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/orchestration/ManualReviewGate.java"
        ));
        var orchestrationService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/orchestration/TaskOrchestrationApplicationService.java"
        ));

        assertThat(manualReviewGate)
            .contains("public class ManualReviewGate")
            .contains("ManualReviewGateResult evaluate(Task task)")
            .contains("PromotionMode.MANUAL")
            .contains("DraftStatus.PENDING_REVIEW")
            .contains("ManualReviewGateResult.notRequired")
            .doesNotContain("HumanInTheLoopApplicationService")
            .doesNotContain("HumanReviewRequestRepository");
        assertThat(orchestrationService)
            .contains("PlanStepRunner")
            .contains("ManualReviewGate")
            .contains("runInitializedTask")
            .contains("REPLANNING_ENABLED_KEY")
            .doesNotContain("com.probeflow.testagent.humanintheloop")
            .doesNotContain("HumanDecisionRecordRepository");
    }

    @Test
    void phase6CriticalApplicationPathsHaveFocusedTests() throws Exception {
        var requiredTests = Map.of(
            "request creation lifecycle",
            "src/test/java/com/probeflow/testagent/humanintheloop/HumanInTheLoopLifecycleTests.java",
            "replanning request creation",
            "src/test/java/com/probeflow/testagent/humanintheloop/ReplanningHumanRequestCreationTests.java",
            "decision submission",
            "src/test/java/com/probeflow/testagent/humanintheloop/HumanDecisionSubmissionTests.java",
            "draft review",
            "src/test/java/com/probeflow/testagent/humanintheloop/DraftReviewHumanWorkflowTests.java",
            "blocker resolution",
            "src/test/java/com/probeflow/testagent/humanintheloop/BlockerResolutionHumanWorkflowTests.java",
            "high-risk approval",
            "src/test/java/com/probeflow/testagent/humanintheloop/HighRiskApprovalHumanWorkflowTests.java",
            "planner clarification",
            "src/test/java/com/probeflow/testagent/humanintheloop/PlannerClarificationHumanWorkflowTests.java",
            "memory candidate handoff",
            "src/test/java/com/probeflow/testagent/humanintheloop/HumanFeedbackMemoryCandidateHandoffTests.java"
        );

        for (var entry : requiredTests.entrySet()) {
            var testPath = PROJECT_ROOT.resolve(entry.getValue());
            assertThat(testPath)
                .as(entry.getKey())
                .exists()
                .isRegularFile();
            assertThat(Files.readString(testPath))
                .as(entry.getKey())
                .contains("@Test");
        }
    }

    private String phase6SourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/humanintheloop"))
            + "\n"
            + sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/replanning"));
    }

    private String mainSourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java"));
    }

    private String sourceText(Path sourceRoot) throws Exception {
        return Files.walk(sourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .filter(path -> !path.toString().contains("/demorun/"))
            .filter(path -> !path.toString().contains("/projectimport/"))
            .filter(path -> !path.toString().contains("/contractsmoke/"))
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
