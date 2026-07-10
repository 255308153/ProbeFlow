package com.probeflow.testagent.projectimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.task.TaskRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
class LocalDirectoryProjectImportReviewBlockerTests {

    private static final Path ALLOWED_ROOT = createTempRoot("probeflow-project-import-review-allowed-");
    private static final Path OUTSIDE_ROOT = createTempRoot("probeflow-project-import-review-outside-");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @DynamicPropertySource
    static void projectImportProperties(DynamicPropertyRegistry registry) {
        registry.add("probeflow.project-import.local-directory.allowed-roots", ALLOWED_ROOT::toString);
    }

    @Test
    void reanalysisRevalidatesAPathMovedOutsideAllowedRootsBeforeCreatingANewTask() throws Exception {
        var project = springProject("moved-project");
        writeController(project.resolve("src/main/java/com/example"), "OrderController", "/orders");
        var imported = importProject(project);
        var taskCountBeforeReanalysis = tasks.count();
        var movedProject = Files.move(project, OUTSIDE_ROOT.resolve(project.getFileName()));

        try {
            Files.createSymbolicLink(project, movedProject);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are not available in this test environment.");
            return;
        }

        mockMvc.perform(post("/api/project-imports/{materialId}/reanalyze", imported.materialId()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("LOCAL_DIRECTORY_PATH_OUTSIDE_ALLOWED_ROOTS"))
            .andExpect(jsonPath("$.field").value("storagePath"));

        assertThat(tasks.count()).isEqualTo(taskCountBeforeReanalysis);
        assertThat(apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(imported.materialId())).hasSize(1);
    }

    @Test
    void successfulImportAndReanalysisReturnWarningsProducedBySourceAnalysis() throws Exception {
        var project = springProject("warnings-project");
        writeController(project.resolve("src/main/java/com/example"), "OrderController", "/orders");
        Files.writeString(project.resolve("src/main/java/com/example/BrokenController.java"), "class BrokenController {");

        var imported = importProject(project);
        assertThat(imported.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo("ANALYSIS_WARNING");
            assertThat(warning.message()).contains("BrokenController.java");
        });

        var reanalyzed = reanalyze(imported.materialId());
        assertThat(reanalyzed.warnings()).singleElement().satisfies(warning ->
            assertThat(warning.message()).contains("BrokenController.java")
        );
    }

    @Test
    void ignoredDirectoriesDoNotContributeFakeControllers() throws Exception {
        var project = springProject("ignored-directories-project");
        var sourceRoot = project.resolve("src/main/java/com/example");
        writeController(sourceRoot, "RealController", "/real");
        for (var ignoredDirectory : new String[] { ".hidden", ".git", "target", "build", "out", "node_modules", ".gradle" }) {
            writeController(sourceRoot.resolve(ignoredDirectory), "FakeController", "/fake-" + ignoredDirectory.replace('.', '-'));
        }

        var imported = importProject(project);
        assertThat(imported.apiSpecCount()).isEqualTo(1);
        assertThat(apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(imported.materialId()))
            .extracting(apiSpec -> apiSpec.getPath())
            .containsExactly("/real");

        var reanalyzed = reanalyze(imported.materialId());
        assertThat(reanalyzed.apiSpecCount()).isEqualTo(1);
    }

    @Test
    void multipleConventionalSourceRootsReturnAnActionableBlockerWithoutMergingModules() throws Exception {
        var project = springProject("multi-module-project");
        writeController(project.resolve("orders/src/main/java/com/example/orders"), "OrderController", "/orders");
        writeController(project.resolve("payments/src/main/java/com/example/payments"), "PaymentController", "/payments");

        var content = mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody(project))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.apiSpecCount").value(0))
            .andExpect(jsonPath("$.blockers[0].code").value("MULTI_MODULE_SOURCE_ROOTS_AMBIGUOUS"))
            .andExpect(jsonPath("$.blockers[0].suggestedAction").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
        var response = objectMapper.readValue(content, ProjectImportResponse.class);

        assertThat(apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(response.materialId())).isEmpty();
        mockMvc.perform(get("/api/project-imports/{materialId}", response.materialId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.blockers[0].code").value("MULTI_MODULE_SOURCE_ROOTS_AMBIGUOUS"));
    }

    private ProjectImportResponse importProject(Path project) throws Exception {
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
            "projectName", "review-service",
            "storagePath", project.toString(),
            "requestedBy", "tester"
        );
    }

    private Path springProject(String name) throws IOException {
        return Files.createTempDirectory(ALLOWED_ROOT, name + "-");
    }

    private void writeController(Path directory, String className, String path) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(className + ".java"), """
            package com.example;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            class %s {
                @GetMapping("%s")
                String get() {
                    return "ok";
                }
            }
            """.formatted(className, path));
    }

    private static Path createTempRoot(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create project import test root", exception);
        }
    }
}
