package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V2Phase8AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void phase8DoesNotAddRestControllersWebConsoleFrontendDashboardOrExperimentPlatform() throws Exception {
        var mainSources = mainSourceText();
        var phase8Sources = phase8SourceText();
        var controllerAnnotations = mainSources.lines()
            .map(String::trim)
            .filter(line -> line.equals("@RestController") || line.equals("@Controller"))
            .toList();

        assertThat(controllerAnnotations).isEmpty();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
        assertThat(presentTerms(mainSources, List.of(
            "EvaluationController",
            "EvaluationRestController",
            "AgentEvaluationController",
            "WebConsole",
            "EvaluationConsole",
            "EvaluationDashboard",
            "BenchmarkDashboard",
            "EvaluationPage",
            "Frontend"
        ))).isEmpty();
        assertThat(presentTerms(phase8Sources, List.of(
            "ABTest",
            "A/B",
            "ExperimentPlatform",
            "OnlineExperiment",
            "ProductionTrafficScoring",
            "TrafficScorer",
            "Leaderboard",
            "BenchmarkPlatform"
        ))).isEmpty();
    }

    @Test
    void phase8DoesNotAddExternalEvaluationSaasNotificationQueueWorkerOrSchedulerDependencies() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var phase8Sources = phase8SourceText();

        assertThat(presentTerms(pom, List.of(
            "openai-evals",
            "langsmith",
            "weightsandbiases",
            "wandb",
            "deepeval",
            "ragas",
            "kafka",
            "rabbitmq",
            "quartz",
            "spring-cloud-stream",
            "hub4j",
            "jira",
            "slack",
            "sendgrid",
            "mailgun",
            "jakarta.mail"
        ))).isEmpty();
        assertThat(presentTerms(phase8Sources, List.of(
            "OpenAIEvals",
            "LangSmith",
            "WeightsAndBiases",
            "DeepEval",
            "Ragas",
            "SlackClient",
            "SlackNotifier",
            "JiraClient",
            "JiraTicket",
            "GitHubIssue",
            "WebhookPublisher",
            "WebhookClient",
            "EmailNotifier",
            "MailSender",
            "@Scheduled",
            "@Async",
            "TaskScheduler",
            "MessageQueue",
            "DistributedWorker",
            "EvaluationWorker",
            "JobQueue",
            "QueueBacked"
        ))).isEmpty();
    }

    @Test
    void regressionEvaluationDefaultsToDeterministicFakesAndDoesNotRequireRealProviders() throws Exception {
        var applicationService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentevaluation/AgentEvaluationApplicationService.java"
        ));
        var datasetRegistry = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentevaluation/EvaluationDatasetRegistry.java"
        ));
        var plannerEvaluator = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentevaluation/PlannerDecisionAccuracyEvaluator.java"
        ));
        var testConfig = Files.readString(PROJECT_ROOT.resolve("src/test/resources/application-test.yml"));

        assertThat(testConfig)
            .contains("allow-real-providers: false")
            .contains("allowed-providers: fake");
        assertThat(applicationService)
            .contains("runDefaultDataset")
            .contains("EvaluationProviderMode.DETERMINISTIC_FAKE")
            .doesNotContain("LlmApplicationService")
            .doesNotContain("WebClient")
            .doesNotContain("RestTemplate");
        assertThat(datasetRegistry)
            .contains("REGRESSION_SUITE_DATASET")
            .contains("uses_real_llm\", false")
            .contains("uses_real_embedding\", false")
            .contains("uses_external_http\", false");
        assertThat(plannerEvaluator)
            .contains("EvaluationProviderMode.DETERMINISTIC_FAKE")
            .contains("FakeControlledPlanner")
            .doesNotContain("LlmCallRequest")
            .doesNotContain("LlmProvider");
    }

    @Test
    void evaluationHarnessStaysBoundedAndDoesNotBecomeAutonomousAgentLoop() throws Exception {
        var phase8Sources = phase8SourceText();
        var applicationService = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentevaluation/AgentEvaluationApplicationService.java"
        ));

        assertThat(presentTerms(phase8Sources, List.of(
            "AutoGPT",
            "AgentLoop",
            "AutonomousAgent",
            "BackgroundEvaluation",
            "SelfImprovingAgent",
            "while (true)",
            "for (;;)"
        ))).isEmpty();
        assertThat(applicationService)
            .contains("for (var fixture : dataset.fixtures())")
            .contains("evaluateFixture(dataset, fixture, context)")
            .doesNotContain("while (")
            .doesNotContain("Thread.sleep")
            .doesNotContain("CompletableFuture")
            .doesNotContain("ExecutorService");
    }

    @Test
    void evaluationReusesPolicyValidatorToolContractAndExistingModuleOwners() throws Exception {
        var policyEvaluator = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentevaluation/ToolSelectionPolicyValidityEvaluator.java"
        ));
        var contextEvaluator = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentevaluation/ContextCitationUsefulnessEvaluator.java"
        ));
        var failureEvaluator = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentevaluation/FailureClassificationAccuracyEvaluator.java"
        ));
        var caseEvaluator = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentevaluation/TestCaseCoverageEvaluator.java"
        ));
        var reportEvaluator = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentevaluation/ReportUsefulnessNoSecretEvaluator.java"
        ));
        var memoryEvaluator = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentevaluation/MemoryReuseClosedLoopEvaluator.java"
        ));

        assertThat(policyEvaluator)
            .contains("ToolContractRegistry")
            .contains("PolicyValidatorService")
            .contains("validator.validate")
            .doesNotContain("new PolicyValidationResult");
        assertThat(contextEvaluator)
            .contains("UnifiedContextBuilder")
            .contains("contextBuilder.build")
            .contains("MemoryRefineryService");
        assertThat(failureEvaluator)
            .contains("FailureAnalysisApplicationService")
            .contains("failureAnalysis.analyzeExecution")
            .doesNotContain("class EvaluationFailureAnalysisService");
        assertThat(caseEvaluator)
            .contains("TestCaseGenerationApplicationService")
            .contains("generationService.generate")
            .doesNotContain("class EvaluationTestCaseGenerator");
        assertThat(reportEvaluator)
            .contains("ReportGenerationApplicationService")
            .contains("reportGeneration.generateTaskReport")
            .contains("hasSecretLeakage")
            .doesNotContain("class EvaluationReportGenerator");
        assertThat(memoryEvaluator)
            .contains("AgentMemoryFeedbackApplicationService")
            .contains("UnifiedContextBuilder")
            .contains("MemoryUsefulnessFeedbackService")
            .doesNotContain("new LongTermMemory()");
    }

    @Test
    void evaluationReportDiagnosticsAreStructuredAndSecretLeakageIsCoveredByTests() throws Exception {
        var report = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentevaluation/EvaluationReport.java"
        ));
        var metric = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/agentevaluation/EvaluationMetricResult.java"
        ));
        var reportEvaluatorTest = Files.readString(PROJECT_ROOT.resolve(
            "src/test/java/com/probeflow/testagent/agentevaluation/ReportUsefulnessNoSecretEvaluatorTests.java"
        ));
        var regressionTest = Files.readString(PROJECT_ROOT.resolve(
            "src/test/java/com/probeflow/testagent/agentevaluation/AgentEvaluationRegressionSuiteTests.java"
        ));

        assertThat(report)
            .contains("runSummary")
            .contains("caseSummary")
            .contains("metricSummary")
            .contains("failedMetrics")
            .contains("recommendedFixes");
        assertThat(metric)
            .contains("diagnosticMessage")
            .contains("actual")
            .contains("expected");
        assertThat(reportEvaluatorTest)
            .contains("reportsMissingSectionsEvidenceRecommendationAndSecretLeakageWithoutEchoingSecretValue")
            .contains("super-secret-report-token")
            .contains("forbidSecretLeakage");
        assertThat(regressionTest)
            .contains("AgentEvaluationRegressionSuiteTests")
            .contains("MAVEN_ENTRYPOINT")
            .contains("failedMetrics");
    }

    @Test
    void phase8CriticalApplicationPathsHaveFocusedTests() throws Exception {
        var requiredTests = Map.of(
            "evaluation foundation",
            "src/test/java/com/probeflow/testagent/agentevaluation/AgentEvaluationApplicationServiceSmokeTests.java",
            "planner evaluator",
            "src/test/java/com/probeflow/testagent/agentevaluation/PlannerDecisionAccuracyEvaluatorTests.java",
            "tool policy evaluator",
            "src/test/java/com/probeflow/testagent/agentevaluation/ToolSelectionPolicyValidityEvaluatorTests.java",
            "context citation evaluator",
            "src/test/java/com/probeflow/testagent/agentevaluation/ContextCitationUsefulnessEvaluatorTests.java",
            "failure evaluator",
            "src/test/java/com/probeflow/testagent/agentevaluation/FailureClassificationAccuracyEvaluatorTests.java",
            "case coverage evaluator",
            "src/test/java/com/probeflow/testagent/agentevaluation/TestCaseCoverageEvaluatorTests.java",
            "report evaluator",
            "src/test/java/com/probeflow/testagent/agentevaluation/ReportUsefulnessNoSecretEvaluatorTests.java",
            "memory reuse evaluator",
            "src/test/java/com/probeflow/testagent/agentevaluation/MemoryReuseClosedLoopEvaluatorTests.java",
            "regression command",
            "src/test/java/com/probeflow/testagent/agentevaluation/AgentEvaluationRegressionSuiteTests.java"
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

    private String phase8SourceText() throws Exception {
        return sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/agentevaluation"));
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
