package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.demorun.DemoRunApplicationService;
import com.probeflow.testagent.demorun.DemoRunProviderMode;
import com.probeflow.testagent.demorun.DemoRunRequest;
import com.probeflow.testagent.demorun.DemoRunStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V4AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @TempDir
    private Path outputDirectory;

    @Test
    void v4AllowsOnlyLocalDemoApiAndDemoConsoleSurface() throws Exception {
        var controllers = Files.walk(PROJECT_ROOT.resolve("src/main/java"))
            .filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().endsWith("Controller.java"))
            .filter(path -> !path.toString().contains("/projectimport/"))
            .filter(path -> !path.toString().contains("/contractsmoke/"))
            .map(path -> path.getFileName().toString())
            .sorted()
            .toList();
        var apiController = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/demorun/DemoRunController.java"
        ));
        var consoleController = Files.readString(PROJECT_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/demorun/DemoConsoleController.java"
        ));
        var consoleHtml = Files.readString(PROJECT_ROOT.resolve(
            "src/main/resources/v4-demo-console/index.html"
        ));

        assertThat(controllers).containsExactly("DemoConsoleController.java", "DemoRunController.java");
        assertThat(apiController)
            .contains("@RequestMapping(\"/api/v4/demo-runs\")")
            .contains("DemoRunApplicationService")
            .doesNotContain("LlmProvider", "ManualRealLlmProvider", "WebClient", "RestTemplate");
        assertThat(consoleController).contains("@GetMapping(value = \"/v4/demo-console\"");
        assertThat(consoleHtml)
            .contains("/api/v4/demo-runs")
            .contains("Fake baseline")
            .contains("Real LLM manual")
            .contains("Comparison");
    }

    @Test
    void defaultDemoRunNeedsNoRealLlmKeyExternalModelEmbeddingOrBusinessHttp() {
        var result = new DemoRunApplicationService()
            .run(DemoRunRequest.fakeBaseline("order-suite-demo", outputDirectory));

        assertThat(result.status()).isEqualTo(DemoRunStatus.COMPLETED);
        assertThat(result.providerMode()).isEqualTo(DemoRunProviderMode.FAKE);
        assertThat(result.usesRealLlm()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(result.provider().llmCalls()).isEmpty();
        assertThat(result.provider().externalDependencyPolicy())
            .contains(
                "default demo run does not require a real LLM key",
                "default demo run does not require real embedding",
                "default demo run uses fake HTTP gateway only"
            );
        assertThat(result.context().summary()).containsEntry("usesRealEmbedding", false);
        assertThat(result.tools().summary()).containsEntry("usesExternalHttp", false);
    }

    @Test
    void realModeWithoutManualConfigurationIsRejectedWithoutCallingExternalModel() {
        var result = new DemoRunApplicationService().run(new DemoRunRequest(
            "order-suite-demo",
            DemoRunProviderMode.REAL,
            "manual-real-llm",
            outputDirectory
        ));

        assertThat(result.status()).isEqualTo(DemoRunStatus.REJECTED);
        assertThat(result.providerMode()).isEqualTo(DemoRunProviderMode.REAL);
        assertThat(result.usesRealLlm()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(result.provider().llmCalls()).isEmpty();
        assertThat(result.diagnostics())
            .extracting(diagnostic -> diagnostic.code())
            .contains("REAL_LLM_NOT_AVAILABLE");
        assertThat(result.errors().summary().toString())
            .contains("endpoint", "key", "model", "timeoutMs", "maxTokens", "costLimitCents");
    }

    @Test
    void comparisonModeKeepsFakeBaselineAndSuppressesLongTermMemoryWritesByDefault() {
        var result = new DemoRunApplicationService().run(new DemoRunRequest(
            "order-suite-demo",
            DemoRunProviderMode.COMPARISON,
            "comparison-demo",
            outputDirectory,
            true,
            true,
            List.of("JSON_REPORT", "MARKDOWN_REPORT")
        ));

        assertThat(result.status()).isEqualTo(DemoRunStatus.COMPLETED);
        assertThat(result.providerMode()).isEqualTo(DemoRunProviderMode.COMPARISON);
        assertThat(result.provider().comparisonEnabled()).isTrue();
        assertThat(result.provider().fakeBaseline()).isTrue();
        assertThat(result.provider().allowMemoryWrite()).isFalse();
        assertThat(result.usesExternalHttp()).isFalse();
        assertThat(result.comparison().summary())
            .containsKeys("fakeBaseline", "realRun")
            .containsEntry("memoryWriteSuppressed", true);
        assertThat(result.memoryFeedback().summary().get("memoryWrite").toString())
            .contains("requested=false", "enabled=false", "actualWritePerformed=false");
    }

    @Test
    void v4DoesNotAddDirectMem0VikingDbProductionAdminOrV5RagProDependencies() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));
        var mainSources = sourceText(PROJECT_ROOT.resolve("src/main/java"));
        var demoRunSources = sourceText(PROJECT_ROOT.resolve("src/main/java/com/probeflow/testagent/demorun"));

        assertThat(presentTerms(pom + "\n" + mainSources, List.of(
            "mem0",
            "vikingdb",
            "viking-db",
            "volcengine-viking",
            "com.volcengine"
        ))).isEmpty();

        assertThat(presentTerms(demoRunSources, List.of(
            "LoginController",
            "TenantController",
            "RbacController",
            "RoleController",
            "UserManagementController",
            "AdminController",
            "TenantManagementService",
            "RbacService",
            "RoleManagementService",
            "ProductionAdmin"
        ))).isEmpty();

        assertThat(presentTerms(pom + "\n" + demoRunSources, List.of(
            "BM25",
            "PG full-text",
            "PostgreSQL full-text",
            "multi-recall",
            "MultiRecall",
            "RRF",
            "ReciprocalRankFusion",
            "Cross-Encoder",
            "CrossEncoder",
            "LLM rerank",
            "LlmRerank",
            "Small-to-Big",
            "SmallToBig"
        ))).isEmpty();
    }

    private String sourceText(Path sourceRoot) throws Exception {
        try (var paths = Files.walk(sourceRoot)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
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
