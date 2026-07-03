package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class Phase5AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void phase5AcceptanceIsCoveredThroughApplicationServiceSeams() throws Exception {
        var phase5Sources = phase5SourceText();
        var generationTests = Files.readString(PROJECT_ROOT.resolve(
            "src/test/java/com/probeflow/testagent/testcasegeneration/TestCaseGenerationApplicationServiceTests.java"
        ));

        assertThat(phase5Sources)
            .contains("class TestCaseGenerationApplicationService")
            .contains("class TestCasePromotionService")
            .contains("enum TestCaseGenerationMode")
            .contains("SINGLE")
            .contains("SUITE")
            .contains("BATCH")
            .contains("CoverageStatus")
            .contains("TargetCoverageSummary")
            .contains("TestCasePromotionRequest");
        assertThat(generationTests)
            .contains("singleModeGeneratesPersistedHappyPathDraftThroughUnifiedContext")
            .contains("repeatedSingleGenerationSuppressesDuplicateDraftsByDedupKey")
            .contains("incompleteApiSpecReturnsCoverageDiagnosticsWithoutCreatingDrafts")
            .contains("suiteModeGeneratesFlowDraftAcrossRelatedApiSpecsAndIsIdempotent")
            .contains("batchModeGeneratesPerApiSpecCoverageAndResumesWithoutDuplicates")
            .contains("promotionCreatesFormalTestCaseWithProvenanceAndIsIdempotent")
            .contains("promotionMapsSuiteDraftToFormalSuiteCase");
    }

    @Test
    void phase5DoesNotImplementExecutionReportsFrontendAutomationOrRealLlmStacks() throws Exception {
        var phase5Sources = phase5SourceText();
        var allSources = Files.walk(PROJECT_ROOT.resolve("src/main/java"))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(this::readUnchecked)
            .collect(Collectors.joining("\n"));
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var controllerAnnotations = allSources.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("@RestController") || line.startsWith("@Controller"))
            .toList();

        assertThat(phase5Sources)
            .doesNotContain("WebClient")
            .doesNotContain("RestTemplate")
            .doesNotContain("java.net.http.HttpClient")
            .doesNotContain("HttpURLConnection")
            .doesNotContain("AssertionEvaluator")
            .doesNotContain("ReportRenderer")
            .doesNotContain("BrowserAutomation")
            .doesNotContain("WebDriver")
            .doesNotContain("Playwright")
            .doesNotContain("Selenium")
            .doesNotContain("OpenAI")
            .doesNotContain("Anthropic")
            .doesNotContain("ChatModel")
            .doesNotContain("streaming")
            .doesNotContain("modelRouter")
            .doesNotContain("service direct invocation")
            .doesNotContain("DB direct assertion");
        assertThat(controllerAnnotations).isEmpty();
        assertThat(pom)
            .doesNotContain("spring-ai")
            .doesNotContain("openai")
            .doesNotContain("anthropic")
            .doesNotContain("playwright")
            .doesNotContain("selenium")
            .doesNotContain("react")
            .doesNotContain("vite")
            .doesNotContain("junit-pioneer-http")
            .doesNotContain("wiremock");
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
    }

    @Test
    void phase5GeneratedBehaviorStaysBlackBoxHttpAssetPreparationOnly() throws Exception {
        var phase5Sources = phase5SourceText();

        assertThat(phase5Sources)
            .contains("Call HTTP API")
            .contains("requestShape")
            .contains("expectedStatus")
            .contains("validationHints")
            .doesNotContain("execute(")
            .doesNotContain("send(")
            .doesNotContain("assertionResults")
            .doesNotContain("responseSnapshot")
            .doesNotContain("JdbcTemplate")
            .doesNotContain("EntityManager")
            .doesNotContain("invokeService")
            .doesNotContain("directInvocation");
    }

    @Test
    void phase5UsesExplicitApplicationSeamsRatherThanAdHocGeneratorClasses() throws Exception {
        var phase5SourceRoot = PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/testcasegeneration");
        var classNames = Files.walk(phase5SourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(path -> path.getFileName().toString().replace(".java", ""))
            .sorted()
            .toList();

        assertThat(classNames).containsExactly(
            "CoverageStatus",
            "ScenarioCategory",
            "ScenarioCoverage",
            "ScenarioIntent",
            "TargetCoverageSummary",
            "TestCaseGenerationApplicationService",
            "TestCaseGenerationCounts",
            "TestCaseGenerationMode",
            "TestCaseGenerationRequest",
            "TestCaseGenerationResult",
            "TestCasePromotionRequest",
            "TestCasePromotionResult",
            "TestCasePromotionService"
        );
        assertThat(classNames).doesNotContain(
            "TestCaseGenerator",
            "TestCaseDraftGenerator",
            "HttpExecutionEngine",
            "ReportRenderer",
            "PromptStreamingService",
            "ModelRouter"
        );
    }

    private String phase5SourceText() throws Exception {
        return Files.walk(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/testcasegeneration"))
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(this::readUnchecked)
            .collect(Collectors.joining("\n"));
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
