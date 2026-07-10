package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentArtifactReference;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentHarness;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentProviderMode;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunRequest;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunResult;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentRunStatus;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSource;
import com.probeflow.testagent.manualsuiteagent.ManualSuiteAgentSectionSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V3Phase6AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @TempDir
    private Path outputDir;

    @Test
    void focusedRegressionTestsCoverV3Phase6AcceptancePathsAndGuardrails() throws Exception {
        var issue01 = testSource("agentmemoryfeedback/AgentMemoryFeedbackSuiteFailureIssue01Tests.java");
        var issue02 = testSource("agentmemoryfeedback/AgentMemoryFeedbackSuiteFailureIssue02Tests.java");
        var issue03 = testSource("agentmemoryfeedback/AgentMemoryFeedbackSuiteHumanCorrectionIssue03Tests.java");
        var issue04 = testSource("agentevaluation/V3SuiteAgentEvaluationIssue04Tests.java");
        var issue05 = testSource("agentevaluation/V3SuiteMemoryReuseIssue05Tests.java");
        var issue06 = testSource("manualsuiteagent/ManualSuiteAgentHarnessIssue06Tests.java");
        var issue07 = testSource("manualsuiteagent/ManualSuiteAgentHarnessIssue07Tests.java");
        var candidateIntake = testSource("agentmemoryfeedback/AgentMemoryFeedbackCandidateIntakeTests.java");

        assertContainsAll(issue01, "suite failure memory feedback", List.of(
            "refineSuiteFailureAnalysisCandidate",
            "VARIABLE_EXTRACTION_FAILURE",
            "rootStep",
            "affectedDownstreamSteps",
            "nextSuggestion",
            "writesLongTermMemory"
        ));
        assertContainsAll(issue02, "suite feedback guardrails", List.of(
            "downstreamLowConfidenceAndHighRiskCandidatesDoNotPolluteDependencyOrLongTermMemoryPaths",
            "DOWNSTREAM_API_FAILURE",
            "PENDING",
            "writesLongTermMemory\", false",
            "refineryInvoked\", false"
        ));
        assertContainsAll(issue03, "human correction feedback", List.of(
            "extract-rule",
            "variable-reference",
            "step-order",
            "business-precondition",
            "HumanInTheLoopApplicationService"
        ));
        assertContainsAll(issue04, "v3 evaluation dataset", List.of(
            "v3-phase-6-suite-agent-capability",
            "V3SuiteAgentCapabilityEvaluator",
            "DETERMINISTIC_FAKE",
            "SECRET_REDACTION_METRIC"
        ));
        assertContainsAll(issue05, "memory reuse closed loop", List.of(
            "V3_SUITE_METRIC_NAME",
            "v3-suite-memory-reuse-order-payment-loop",
            "manualRealExperimentIsExplicitCiIsolatedAndDoesNotWriteLongTermMemoryByDefault",
            "usesRealProvider\", false",
            "writesLongTermMemory\", false"
        ));
        assertContainsAll(issue06, "harness memory feedback", List.of(
            "memory-feedback",
            "ManualSuiteAgentSectionSource.REAL",
            "failurePathRunsRealMemoryFeedbackThroughApplicationServiceAndRefinery",
            "refinerySummary",
            "writesLongTermMemory"
        ));
        assertContainsAll(issue07, "harness evaluation comparison", List.of(
            "evaluation-comparison",
            "v3-phase-6-suite-agent-capability",
            "ManualSuiteAgentSectionSource.REAL",
            "overallScore"
        ));
        assertContainsAll(candidateIntake, "intake duplicate and sanitizer guards", List.of(
            "duplicateSourceTypeRefAndTaskReturnsIdempotentDuplicateWithoutNewRecord",
            "masksSensitiveFieldsInCandidateContentEvidenceMetadataAndAuditSummary",
            "rejectsCompletedOrCancelledTaskCandidatesWithoutHalfWrite",
            "writesLongTermMemory"
        ));
    }

    @Test
    void v3Phase6MemoryAndEvaluationModulesDoNotRegenerateEarlierPhaseArtifactsOrRuntimeState() throws Exception {
        var v36Source = sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/agentmemoryfeedback"))
            + "\n"
            + Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/com/probeflow/testagent/agentevaluation/AgentEvaluationApplicationService.java"
            ))
            + "\n"
            + Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/com/probeflow/testagent/agentevaluation/EvaluationDatasetRegistry.java"
            ))
            + "\n"
            + Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/com/probeflow/testagent/agentevaluation/V3SuiteAgentCapabilityEvaluator.java"
            ))
            + "\n"
            + Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/com/probeflow/testagent/agentevaluation/MemoryReuseClosedLoopEvaluator.java"
            ));

        assertThat(presentTerms(v36Source, List.of(
            "BusinessFlowCandidate",
            "BusinessFlowDiscoveryService",
            "SuiteDraftGenerationService",
            "SuiteDraftGenerationRequest",
            "SuiteExtractRule(",
            "SuiteVariableReference(",
            "DependencyLinker",
            "VariableResolver",
            "ResponseExtractor",
            "VariableWriteBackService",
            "FailureAnalysisApplicationService",
            "analyzeExecution(",
            "analyzeTask(",
            "autoFix",
            "applyRecovery",
            "insertRecoveryStep",
            "rerunFailedStep",
            "setRequestTemplate(",
            "setSteps(",
            "setOrder("
        ))).isEmpty();

        assertThat(v36Source)
            .contains("HumanInTheLoopApplicationService")
            .contains("MemoryRefineryService")
            .contains("PolicyValidator");
    }

    @Test
    void v3Phase6DoesNotIntroduceFullRagMemoryFrontendRealProviderOrExternalHttpSurfaces() throws Exception {
        var mainSources = sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent"));
        var v36Source = sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/agentmemoryfeedback"))
            + "\n"
            + Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/com/probeflow/testagent/agentevaluation/AgentEvaluationApplicationService.java"
            ))
            + "\n"
            + Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/com/probeflow/testagent/agentevaluation/EvaluationDatasetRegistry.java"
            ))
            + "\n"
            + Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/com/probeflow/testagent/agentevaluation/V3SuiteAgentCapabilityEvaluator.java"
            ))
            + "\n"
            + Files.readString(PROJECT_ROOT.resolve(
                "src/main/java/com/probeflow/testagent/agentevaluation/MemoryReuseClosedLoopEvaluator.java"
            ));
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var controllerAnnotations = mainSources.lines()
            .map(String::trim)
            .filter(line -> line.equals("@RestController") || line.equals("@Controller"))
            .toList();

        assertThat(controllerAnnotations).isEmpty();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
        assertThat(presentTerms(v36Source, List.of(
            "mem0",
            "LightConsoleController",
            "RagApplicationService",
            "VectorStore",
            "PgVector",
            "BM25",
            "QueryRewrite",
            "Rerank",
            "SmallToBig",
            "Small-to-Big"
        ))).isEmpty();
        assertThat(presentTerms(mainSources + "\n" + pom, List.of(
            "mem0",
            "LightConsoleController",
            "OpenAI",
            "Anthropic",
            "Ollama",
            "Bedrock",
            "RestTemplate",
            "WebClient",
            "java.net.http.HttpClient",
            "HttpURLConnection",
            "Playwright",
            "Selenium",
            "Cypress",
            "Puppeteer",
            "spring-ai",
            "langchain"
        ))).isEmpty();
    }

    @Test
    void manualHarnessRunsCompleteDeterministicV3Phase6FailureLearningAndEvaluationLoop() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-variable-extraction-failure", outputDir));

        assertThat(result.status()).isEqualTo(ManualSuiteAgentRunStatus.COMPLETED);
        assertThat(result.providerMode()).isEqualTo(ManualSuiteAgentProviderMode.DETERMINISTIC_FAKE);
        assertThat(result.usesRealProvider()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();

        assertRealSections(result, List.of(
            "business-flow-discovery",
            "generated-suite-draft",
            "execution-result",
            "variable-audit",
            "failure-analysis",
            "memory-feedback",
            "evaluation-comparison"
        ));

        assertThat(section(result, "failure-analysis").summary())
            .containsEntry("classification", "VARIABLE_EXTRACTION_FAILURE")
            .containsEntry("rootStep", "create-order")
            .containsEntry("recoveryActionType", "FIX_EXTRACT_RULE");
        assertThat(section(result, "memory-feedback").summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("classification", "VARIABLE_EXTRACTION_FAILURE")
            .containsEntry("writesLongTermMemory", true);
        assertThat(section(result, "memory-feedback").status()).isIn("ACCEPTED", "MERGED");
        assertThat(section(result, "evaluation-comparison").summary())
            .containsEntry("sourceMarker", "real")
            .containsEntry("dataset", "v3-phase-6-suite-agent-capability")
            .containsEntry("runStatus", "PASSED")
            .containsEntry("usesRealProvider", false)
            .containsEntry("usesExternalHttp", false);

        var jsonText = Files.readString(artifactPath(result, "JSON_REPORT"));
        var markdown = Files.readString(artifactPath(result, "MARKDOWN_REPORT"));
        var json = new ObjectMapper().readTree(jsonText);
        assertThat(json.at("/run/usesRealLlm").asBoolean()).isFalse();
        assertThat(json.at("/run/usesExternalHttp").asBoolean()).isFalse();
        assertThat(json.at("/metadata/memoryFeedbackStatus").asText()).isIn("ACCEPTED", "MERGED");
        assertThat(json.at("/metadata/evaluationDataset").asText()).isEqualTo("v3-phase-6-suite-agent-capability");
        assertThat(json.at("/metadata/evaluationRunStatus").asText()).isEqualTo("PASSED");
        assertThat(markdown)
            .contains("memory-feedback (REAL")
            .contains("Writes long-term memory: true")
            .contains("evaluation-comparison (REAL, PASSED)")
            .contains("Dataset: v3-phase-6-suite-agent-capability");
        assertNoSensitiveValues(jsonText);
        assertNoSensitiveValues(markdown);
    }

    @Test
    void noFailureHappyPathIsNotLearnableAndDoesNotWriteLongTermMemory() throws Exception {
        var result = ManualSuiteAgentHarness.defaults()
            .run(ManualSuiteAgentRunRequest.fake("order-suite-demo", outputDir));

        assertThat(result.status()).isEqualTo(ManualSuiteAgentRunStatus.COMPLETED);
        assertThat(result.usesRealProvider()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(section(result, "failure-analysis").summary())
            .containsEntry("classification", "NONE")
            .containsEntry("recoveryActionType", "NO_ACTION");
        assertThat(section(result, "memory-feedback").summary())
            .containsEntry("candidateStatus", "REJECTED")
            .containsEntry("rejectionReason", "suite-failure-not-learnable")
            .containsEntry("writesLongTermMemory", false);
        assertThat(section(result, "evaluation-comparison").summary())
            .containsEntry("runStatus", "PASSED")
            .containsEntry("usesRealProvider", false);
    }

    @Test
    void readmeDescribesTheV3Phase6LightweightAgentLoopAndBoundary() throws Exception {
        var readme = Files.readString(PROJECT_ROOT.resolve("README.md"));

        assertThat(readme)
            .contains("V3-6")
            .contains("Memory Feedback")
            .contains("Agent Evaluation")
            .contains("Manual Suite Agent Harness")
            .contains("deterministic fake")
            .contains("不依赖真实 LLM、真实 embedding 或真实外部 HTTP")
            .contains("不重做 V3-2、V3-3、V3-4、V3-5")
            .contains("感知上下文、规划链路、调用工具执行、诊断失败、形成经验、评估能力、可视化演示");
    }

    private String testSource(String relativePath) throws Exception {
        return Files.readString(PROJECT_ROOT.resolve("src/test/java/com/probeflow/testagent").resolve(relativePath));
    }

    private void assertContainsAll(String text, String description, List<String> snippets) {
        assertThat(text).as(description).contains(snippets.toArray(String[]::new));
    }

    private void assertRealSections(ManualSuiteAgentRunResult result, List<String> sectionIds) {
        for (var sectionId : sectionIds) {
            assertThat(section(result, sectionId).source())
                .as(sectionId)
                .isEqualTo(ManualSuiteAgentSectionSource.REAL);
        }
    }

    private ManualSuiteAgentSectionSummary section(ManualSuiteAgentRunResult result, String sectionId) {
        return result.sections().stream()
            .filter(section -> section.sectionId().equals(sectionId))
            .findFirst()
            .orElseThrow();
    }

    private Path artifactPath(ManualSuiteAgentRunResult result, String artifactType) {
        return result.artifacts().stream()
            .filter(artifact -> artifact.artifactType().equals(artifactType))
            .findFirst()
            .map(ManualSuiteAgentArtifactReference::path)
            .map(Path::of)
            .orElseThrow();
    }

    private String sourceText(Path sourceRoot) throws Exception {
        try (var paths = Files.walk(sourceRoot)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.toString().contains("/demorun/"))
                .filter(path -> !path.toString().contains("/projectimport/"))
                .map(this::readUnchecked)
                .collect(Collectors.joining("\n"));
        }
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }

    private List<String> presentTerms(String text, List<String> terms) {
        var normalized = text.toLowerCase(Locale.ROOT);
        return terms.stream()
            .filter(term -> normalized.contains(term.toLowerCase(Locale.ROOT)))
            .toList();
    }

    private void assertNoSensitiveValues(String text) {
        assertThat(text)
            .doesNotContain(
                "order-demo-token",
                "Bearer order-demo-token",
                "session-cookie-secret",
                "Authorization: Bearer",
                "api-key-123",
                "sk_live_order_secret",
                "real-secret-token",
                "cookie-value"
            )
            .contains("[REDACTED]");
    }
}
