package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.projectimport.ProjectImportApiSpecResponse;
import com.probeflow.testagent.projectimport.ProjectImportResponse;
import com.probeflow.testagent.sourcematerial.MaterialType;
import com.probeflow.testagent.sourcematerial.SourceMaterialRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V6Phase1AcceptanceBoundaryGuardTests {

    private static final Path BACKEND_ROOT = Path.of("").toAbsolutePath();
    private static final Path V6_1_DOC = BACKEND_ROOT.resolve("docs/v6-1-local-folder-project-import.md");
    private static final Path ALLOWED_ROOT = createTempRoot("probeflow-v6-1-acceptance-allowed-");
    private static final Path OUTSIDE_ROOT = createTempRoot("probeflow-v6-1-acceptance-outside-");
    private static final TypeReference<List<ProjectImportApiSpecResponse>> API_SPEC_LIST =
        new TypeReference<>() {
        };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SourceMaterialRepository sourceMaterials;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @DynamicPropertySource
    static void projectImportProperties(DynamicPropertyRegistry registry) {
        registry.add("probeflow.project-import.local-directory.allowed-roots", ALLOWED_ROOT::toString);
    }

    @Test
    void chineseManualVerificationDocumentExplainsEntryConfigAndAlphaGate() throws Exception {
        var doc = Files.readString(V6_1_DOC);
        var readme = Files.readString(BACKEND_ROOT.resolve("README.md"));

        assertThat(readme)
            .contains("V6-1 Local Folder Project Import")
            .contains("docs/v6-1-local-folder-project-import.md");
        assertThat(doc)
            .contains("本地路径是 **ProbeFlow 服务端机器路径**")
            .contains("不返回源码正文")
            .contains("PROBEFLOW_PROJECT_IMPORT_LOCAL_DIRECTORY_ALLOWED_ROOTS")
            .contains("POST")
            .contains("/api/project-imports/local-directory")
            .contains("GET")
            .contains("/api/project-imports/{materialId}")
            .contains("/api/project-imports/{materialId}/api-specs")
            .contains("/api/project-imports/{materialId}/reanalyze")
            .contains("LOCAL_DIRECTORY_PATH_NOT_ABSOLUTE")
            .contains("LOCAL_DIRECTORY_PATH_OUTSIDE_ALLOWED_ROOTS")
            .contains("NO_APIS_FOUND")
            .contains("NO_HTTP_APIS_FOUND")
            .contains("MULTI_MODULE_SOURCE_ROOTS_AMBIGUOUS")
            .contains("presentInLatestAnalysis")
            .contains("不自动启动被测服务")
            .contains("DeepSeek V4 Pro")
            .contains("不能作为内部试用的唯一验收依据")
            .contains("V5 完成")
            .contains("V6-1 完成")
            .contains("默认验证与 CI **不依赖**")
            .contains("不包含 V6-2 Schemathesis / OpenAPI 契约测试")
            .contains("SourceMaterial")
            .contains("ApiSpec");
    }

    @Test
    void stageAcceptanceCoversImportDetailReanalysisHistoryIgnoreRulesAndBlockers() throws Exception {
        var project = Files.createTempDirectory(ALLOWED_ROOT, "orders-acceptance-");
        writeController(project.resolve("src/main/java/com/example"), "OrderController", """
            package com.example;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            class OrderController {
                @GetMapping("/api/orders/{orderId}")
                String get() { return "ok"; }

                @PostMapping("/api/orders")
                String create() { return "created"; }
            }
            """);
        writeController(project.resolve("src/main/java/com/example/target"), "FakeTargetController", """
            package com.example;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            class FakeTargetController {
                @GetMapping("/api/fake-target")
                String get() { return "fake"; }
            }
            """);
        Files.writeString(
            project.resolve("src/main/java/com/example/BrokenController.java"),
            "class BrokenController {"
        );

        var imported = importLocalDirectory(project);
        assertThat(imported.status()).isEqualTo("READY");
        assertThat(imported.materialType()).isEqualTo("SOURCE_DIRECTORY");
        assertThat(imported.apiSpecCount()).isEqualTo(2);
        assertThat(imported.warnings()).isNotEmpty();
        assertThat(imported.blockers()).isEmpty();

        var material = sourceMaterials.findById(imported.materialId()).orElseThrow();
        assertThat(material.getMaterialType()).isEqualTo(MaterialType.SOURCE_DIRECTORY);
        assertThat(material.getStoragePath()).isNotBlank();

        mockMvc.perform(get("/api/project-imports/{materialId}", imported.materialId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.materialId").value(imported.materialId()))
            .andExpect(jsonPath("$.materialType").value("SOURCE_DIRECTORY"))
            .andExpect(jsonPath("$.apiSpecCount").value(2))
            .andExpect(jsonPath("$.taskId").value(imported.taskId()));

        var specsContent = mockMvc.perform(get("/api/project-imports/{materialId}/api-specs", imported.materialId()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        var specs = objectMapper.readValue(specsContent, API_SPEC_LIST);
        assertThat(specs).hasSize(2);
        assertThat(specs).allSatisfy(spec -> {
            assertThat(spec.presentInLatestAnalysis()).isTrue();
            assertThat(objectMapper.writeValueAsString(spec)).doesNotContain("return \"ok\"");
            assertThat(spec.sourceLocation())
                .containsKeys("filePath", "className", "methodName")
                .doesNotContainKeys("source", "sourceCode", "source_code", "content", "body", "snippet", "text");
        });
        assertThat(specs).extracting(ProjectImportApiSpecResponse::path)
            .containsExactly("/api/orders", "/api/orders/{orderId}");
        assertThat(specs).extracting(ProjectImportApiSpecResponse::path)
            .doesNotContain("/api/fake-target");

        writeController(project.resolve("src/main/java/com/example"), "OrderController", """
            package com.example;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            class OrderController {
                @GetMapping("/api/orders/{orderId}")
                String get() { return "ok"; }
            }
            """);

        var reanalyzed = reanalyze(imported.materialId());
        assertThat(reanalyzed.materialId()).isEqualTo(imported.materialId());
        assertThat(reanalyzed.apiSpecCount()).isEqualTo(1);
        assertThat(reanalyzed.taskId()).isNotEqualTo(imported.taskId());

        var afterReanalysis = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(imported.materialId());
        assertThat(afterReanalysis).hasSize(2);
        assertThat(afterReanalysis).filteredOn(spec -> spec.isPresentInLatestAnalysis()).hasSize(1);
        assertThat(afterReanalysis).filteredOn(spec -> !spec.isPresentInLatestAnalysis()).hasSize(1);

        mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "projectName", "relative-path",
                    "storagePath", "relative-orders",
                    "requestedBy", "tester"
                ))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("LOCAL_DIRECTORY_PATH_NOT_ABSOLUTE"));

        var outsideProject = Files.createTempDirectory(OUTSIDE_ROOT, "outside-project-");
        mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "projectName", "outside-root",
                    "storagePath", outsideProject.toString(),
                    "requestedBy", "tester"
                ))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("LOCAL_DIRECTORY_PATH_OUTSIDE_ALLOWED_ROOTS"));

        var emptyProject = Files.createTempDirectory(ALLOWED_ROOT, "empty-project-");
        mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody(emptyProject))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.blockers[0].code").value("NO_APIS_FOUND"));

        var noControllerProject = Files.createTempDirectory(ALLOWED_ROOT, "no-controller-");
        Files.createDirectories(noControllerProject.resolve("src/main/java/com/example"));
        Files.writeString(
            noControllerProject.resolve("src/main/java/com/example/Helper.java"),
            "package com.example; class Helper {}"
        );
        mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody(noControllerProject))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.blockers[0].code").value("NO_HTTP_APIS_FOUND"));

        var multiModule = Files.createTempDirectory(ALLOWED_ROOT, "multi-module-");
        writeController(multiModule.resolve("orders/src/main/java/com/example/orders"), "OrderController", """
            package com.example.orders;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController class OrderController {
                @GetMapping("/orders") String get() { return "ok"; }
            }
            """);
        writeController(multiModule.resolve("payments/src/main/java/com/example/payments"), "PaymentController", """
            package com.example.payments;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;
            @RestController class PaymentController {
                @GetMapping("/payments") String get() { return "ok"; }
            }
            """);
        mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody(multiModule))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.blockers[0].code").value("MULTI_MODULE_SOURCE_ROOTS_AMBIGUOUS"));
    }

    @Test
    void defaultTestsRemainFakeOfflineAndV6_1DoesNotExpandImportPlatformScope() throws Exception {
        var applicationYaml = Files.readString(BACKEND_ROOT.resolve("src/main/resources/application.yml"));
        var testYaml = Files.readString(BACKEND_ROOT.resolve("src/test/resources/application-test.yml"));
        var projectImportSources = sourceText(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/projectimport"
        ));
        var analyzerSource = Files.readString(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/analysis/SpringSourceAnalyzer.java"
        ));
        var serviceSource = Files.readString(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/projectimport/LocalDirectoryProjectImportApplicationService.java"
        ));
        var productionSurface = Files.readString(BACKEND_ROOT.resolve("pom.xml"))
            + "\n"
            + sourceText(BACKEND_ROOT.resolve("src/main/resources"))
            + "\n"
            + sourceText(BACKEND_ROOT.resolve("src/main/java"));

        assertThat(applicationYaml)
            .contains("allowed-roots: ${PROBEFLOW_PROJECT_IMPORT_LOCAL_DIRECTORY_ALLOWED_ROOTS:}")
            .contains("allow-real-providers: ${PROBEFLOW_LLM_ALLOW_REAL_PROVIDERS:false}")
            .contains("allowed-providers: ${PROBEFLOW_LLM_ALLOWED_PROVIDERS:fake}")
            .contains("enabled: ${PROBEFLOW_LLM_MANUAL_REAL_ENABLED:false}");
        assertThat(testYaml)
            .contains("allow-real-providers: false")
            .contains("allowed-providers: fake")
            .contains("enabled: false");

        assertThat(serviceSource)
            .contains("pathValidator.validate(material.getStoragePath())")
            .contains("MaterialType.SOURCE_DIRECTORY")
            .contains("ApiAnalysisApplicationService")
            .contains("SourceMaterialRepository")
            .contains("ApiSpecRepository")
            .contains("TaskRepository")
            .contains("warnings(")
            .contains("sourceLocationWithoutSourceBody");
        assertThat(analyzerSource)
            .contains("IGNORED_DIRECTORY_NAMES")
            .contains("target")
            .contains("build")
            .contains("out")
            .contains("node_modules")
            .contains("MULTI_MODULE_SOURCE_ROOTS_AMBIGUOUS")
            .contains("startsWith(\".\")");

        assertThat(projectImportSources)
            .contains("/api/project-imports")
            .contains("local-directory")
            .contains("reanalyze")
            .doesNotContain("GitClone")
            .doesNotContain("git clone")
            .doesNotContain("JGit")
            .doesNotContain("ZipFile")
            .doesNotContain("MultipartFile")
            .doesNotContain("Schemathesis")
            .doesNotContain("schemathesis")
            .doesNotContain("OpenApiUpload")
            .doesNotContain("SwaggerUpload");

        assertThat(presentTerms(productionSurface, List.of(
            "schemathesis",
            "io.schemathesis",
            "hypothesis-python-schemathesis"
        ))).isEmpty();

        assertThat(Files.exists(BACKEND_ROOT.resolve("src/main/resources/v6-1-project-import-console/index.html")))
            .isFalse();
        assertThat(projectImportSources).doesNotContain("ProcessBuilder");
        assertThat(serviceSource).doesNotContain("Runtime.getRuntime().exec");
    }

    @Test
    void v6_1ReusesExistingAssetModelsAndDoesNotCreateSecondImportAssetSystem() throws Exception {
        var projectImportSources = sourceText(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/projectimport"
        ));
        var mainPackages = listImmediateSubdirectories(
            BACKEND_ROOT.resolve("src/main/java/com/probeflow/testagent")
        );

        assertThat(projectImportSources)
            .contains("com.probeflow.testagent.sourcematerial.SourceMaterial")
            .contains("com.probeflow.testagent.task.Task")
            .contains("com.probeflow.testagent.apispec.ApiSpec")
            .contains("com.probeflow.testagent.analysis.ApiAnalysisApplicationService");
        assertThat(mainPackages)
            .contains(
                "sourcematerial",
                "task",
                "apispec",
                "analysis",
                "executionrecord",
                "observation",
                "report",
                "projectimport"
            )
            .doesNotContain(
                "importedproject",
                "projectasset",
                "localprojectregistry",
                "secondmaterial",
                "openapiimport"
            );
        assertThat(Files.exists(BACKEND_ROOT.resolve(
            "src/main/java/com/probeflow/testagent/importedproject"
        ))).isFalse();
    }

    private ProjectImportResponse importLocalDirectory(Path project) throws Exception {
        var content = mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody(project))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readValue(content, ProjectImportResponse.class);
    }

    private ProjectImportResponse reanalyze(String materialId) throws Exception {
        var content = mockMvc.perform(post("/api/project-imports/{materialId}/reanalyze", materialId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readValue(content, ProjectImportResponse.class);
    }

    private Map<String, Object> requestBody(Path project) {
        return Map.of(
            "projectName", "v6-1-acceptance",
            "storagePath", project.toString(),
            "requestedBy", "tester"
        );
    }

    private void writeController(Path directory, String className, String source) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(className + ".java"), source);
    }

    private List<String> listImmediateSubdirectories(Path root) throws IOException {
        try (var paths = Files.list(root)) {
            return paths
                .filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
        }
    }

    private String sourceText(Path sourceRoot) throws IOException {
        try (var paths = Files.walk(sourceRoot)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> {
                    var file = path.toString();
                    return file.endsWith(".java") || file.endsWith(".xml") || file.endsWith(".yml") || file.endsWith(".sql");
                })
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

    private static Path createTempRoot(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create V6-1 acceptance root", exception);
        }
    }
}
