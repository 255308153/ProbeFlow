package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V2Phase7AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void phase7DoesNotAddRestControllersWebConsoleFrontendComplexPermissionsOrExternalIntegrations() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var mainSources = mainSourceText();
        var phase7Sources = phase7SourceText();
        var controllerAnnotations = mainSources.lines()
            .map(String::trim)
            .filter(line -> line.equals("@RestController") || line.equals("@Controller"))
            .toList();

        assertThat(controllerAnnotations).isEmpty();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
        assertThat(presentTerms(mainSources, List.of(
            "WebConsole",
            "MemoryReviewController",
            "MemoryGovernanceController",
            "MemoryReviewPage",
            "MemoryDashboard",
            "Frontend"
        ))).isEmpty();
        assertThat(presentTerms(phase7Sources, List.of(
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
            "MemoryApproverGroup",
            "MultiUser",
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
    }

    @Test
    void phase7DoesNotAddAsyncQueueDistributedWorkerBackgroundLearningOrPhase8EvaluationHarness() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var phase7Sources = phase7SourceText();

        assertThat(presentTerms(phase7Sources, List.of(
            "@Scheduled",
            "@Async",
            "TaskScheduler",
            "BackgroundLearning",
            "AutonomousLearning",
            "MemoryLearningDaemon",
            "Kafka",
            "Rabbit",
            "MessageQueue",
            "DistributedWorker",
            "MemoryFeedbackWorker",
            "JobQueue",
            "QueueBacked",
            "AgentEvaluationService",
            "AgentEvaluator",
            "AgentEvaluationHarness",
            "EvaluationDataset",
            "EvaluationRun"
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
            "bedrock"
        ))).isEmpty();
    }

    @Test
    void realLlmIsNotCiDependencyAndLlmCannotDirectlyWriteLongTermMemory() throws Exception {
        var testConfig = Files.readString(PROJECT_ROOT.resolve("src/test/resources/application-test.yml"));
        var llmSources = sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/llm"));
        var phase7Sources = phase7SourceTextWithoutOptionalLlmFactExtractor();
        var optionalFactExtractor = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/LlmAssistedMemoryFactExtractor.java"
        ));
        var memoryFeedbackService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentmemoryfeedback/AgentMemoryFeedbackApplicationService.java"
        ));

        assertThat(testConfig)
            .contains("allow-real-providers: false")
            .contains("allowed-providers: fake");
        assertThat(llmSources)
            .doesNotContain("LongTermMemoryRepository")
            .doesNotContain("MemoryRefineryService")
            .doesNotContain("LongTermMemory");
        assertThat(phase7Sources)
            .doesNotContain("LlmApplicationService")
            .doesNotContain("LlmCallRequest")
            .doesNotContain("LlmProvider");
        assertThat(optionalFactExtractor)
            .contains("ConditionalOnProperty")
            .contains("havingValue = \"llm-assisted\"")
            .contains("LlmApplicationService")
            .doesNotContain("LongTermMemoryRepository")
            .doesNotContain("MemoryRefineryService")
            .doesNotContain("new LongTermMemory");
        assertThat(memoryFeedbackService)
            .contains("MemoryRefineryService")
            .contains("memoryRefinery.refine")
            .contains("sanitizeMemoryCandidate")
            .contains("submitCandidate")
            .doesNotContain("new LongTermMemory")
            .doesNotContain("LongTermMemoryRepository");
    }

    @Test
    void unvalidatedHumanInputCannotEnterLongTermMemoryAndRawRecordsArePreserved() throws Exception {
        var memoryFeedbackService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentmemoryfeedback/AgentMemoryFeedbackApplicationService.java"
        ));
        var memoryRefineryService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/MemoryRefineryService.java"
        ));
        var humanService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/humanintheloop/HumanInTheLoopApplicationService.java"
        ));
        var phase7Sources = phase7SourceText();

        assertThat(memoryFeedbackService)
            .contains("refineHumanDecisionCandidate")
            .contains("generateMemoryCandidateForDecision")
            .contains("HumanFeedbackMemoryCandidateStatus.GENERATED")
            .contains("sanitizeMemoryCandidate")
            .contains("submitAndRefine")
            .contains("validate(normalized)")
            .contains("writesLongTermMemory");
        assertThat(memoryRefineryService)
            .contains("MIN_CONFIDENCE")
            .contains("rejectionReason")
            .contains("classify")
            .contains("findMergeCandidate")
            .contains("longTermMemories.save");
        assertThat(humanService)
            .contains("validatePayload")
            .contains("sanitizeMap")
            .contains("MASKED_VALUE")
            .doesNotContain("LongTermMemoryRepository")
            .doesNotContain("MemoryRefineryService");
        assertThat(phase7Sources)
            .doesNotContain("ObservationRepository")
            .doesNotContain("ExecutionRecordRepository.delete")
            .doesNotContain("HumanDecisionRecordRepository.delete")
            .doesNotContain("PolicyValidationResult.delete")
            .doesNotContain(".deleteAll(")
            .doesNotContain(".deleteById(");
    }

    @Test
    void existingMemoryContextFailureAnalysisAndHumanWorkflowRemainOwnedByTheirOriginalServices() throws Exception {
        var memoryRefineryService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/MemoryRefineryService.java"
        ));
        var unifiedContextBuilder = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/UnifiedContextBuilder.java"
        ));
        var failureAnalysisService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/failureanalysis/FailureAnalysisApplicationService.java"
        ));
        var humanService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/humanintheloop/HumanInTheLoopApplicationService.java"
        ));

        assertThat(memoryRefineryService)
            .contains("public class MemoryRefineryService")
            .contains("public MemoryRefineryResult refine(MemoryCandidateRequest request)");
        assertThat(unifiedContextBuilder)
            .contains("public class UnifiedContextBuilder")
            .contains("LongTermMemoryRetrievalService")
            .contains("MemoryUsageRecordingService")
            .contains("buildCitations")
            .contains("recordLongTermMemoryUsage");
        assertThat(failureAnalysisService)
            .contains("public class FailureAnalysisApplicationService")
            .contains("AgentMemoryFeedbackApplicationService")
            .contains("refineFailureAnalysisCandidate")
            .contains("memoryCandidate");
        assertThat(humanService)
            .contains("public class HumanInTheLoopApplicationService")
            .contains("generateMemoryCandidateForDecision")
            .doesNotContain("MemoryRefineryService")
            .doesNotContain("LongTermMemoryRepository");
    }

    @Test
    void phase7CriticalApplicationPathsHaveFocusedTests() throws Exception {
        var requiredTests = Map.of(
            "candidate intake",
            "src/test/java/com/probeflow/testagent/agentmemoryfeedback/AgentMemoryFeedbackCandidateIntakeTests.java",
            "human feedback refine",
            "src/test/java/com/probeflow/testagent/agentmemoryfeedback/AgentMemoryFeedbackHumanDecisionRefineryTests.java",
            "execution failure intake",
            "src/test/java/com/probeflow/testagent/agentmemoryfeedback/AgentMemoryFeedbackExecutionStepOutcomeCandidateTests.java",
            "policy learning note",
            "src/test/java/com/probeflow/testagent/agentmemoryfeedback/AgentMemoryFeedbackPolicyPlannerLearningNoteTests.java",
            "memory merge reinforcement",
            "src/test/java/com/probeflow/testagent/memory/MemoryRefineryServiceTests.java",
            "usage record",
            "src/test/java/com/probeflow/testagent/memory/UnifiedContextBuilderTests.java",
            "usefulness feedback",
            "src/test/java/com/probeflow/testagent/memory/MemoryUsefulnessFeedbackServiceTests.java",
            "next-task recall",
            "src/test/java/com/probeflow/testagent/agentmemoryfeedback/AgentMemoryFeedbackClosedLoopTests.java"
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

    private String phase7SourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/agentmemoryfeedback"))
            + "\n"
            + sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/memory"));
    }

    private String phase7SourceTextWithoutOptionalLlmFactExtractor() throws Exception {
        return phase7SourceText().replace(Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/memory/LlmAssistedMemoryFactExtractor.java"
        )), "");
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
