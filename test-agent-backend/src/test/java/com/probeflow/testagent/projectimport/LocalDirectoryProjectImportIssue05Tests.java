package com.probeflow.testagent.projectimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.sourcematerial.IngestStatus;
import com.probeflow.testagent.sourcematerial.SourceMaterialRepository;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
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
class LocalDirectoryProjectImportIssue05Tests {

    private static final Path ALLOWED_ROOT = createTempRoot("probeflow-project-import-issue05-allowed-");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SourceMaterialRepository sourceMaterials;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @DynamicPropertySource
    static void projectImportProperties(DynamicPropertyRegistry registry) {
        registry.add("probeflow.project-import.local-directory.allowed-roots", ALLOWED_ROOT::toString);
    }

    @Test
    void emptyDirectoryReturnsSourceBlockerInCreateAndDetailWithoutApiSpecs() throws Exception {
        var projectDir = Files.createTempDirectory(ALLOWED_ROOT, "empty-service-");

        var response = importFailed(projectDir, "NO_APIS_FOUND", "No analyzable source code found");

        assertThat(response.blockers()).containsExactly(new ProjectImportDiagnostic(
            "NO_APIS_FOUND",
            "No analyzable source code found",
            "No Java source files were found in the local directory.",
            "Add Java source files to the selected directory, then rerun analysis."
        ));
        assertFailedArtifacts(response);
        assertDetailBlocker(response.materialId(), response.blockers().getFirst());
    }

    @Test
    void sourceWithoutHttpInterfacesReturnsDedicatedBlockerWithoutSourceBodyLeakage() throws Exception {
        var projectDir = Files.createTempDirectory(ALLOWED_ROOT, "no-http-service-");
        var secretSource = "SOURCE_BODY_MUST_NOT_LEAK";
        var sourceFile = Files.createDirectories(projectDir.resolve("src/main/java/com/example"))
            .resolve("OrderService.java");
        Files.writeString(sourceFile, """
            package com.example;

            class OrderService {
                private static final String API_KEY = "%s";
            }
            """.formatted(secretSource));

        var response = importFailed(projectDir, "NO_HTTP_APIS_FOUND", "No HTTP interfaces found");

        assertThat(response.blockers()).containsExactly(new ProjectImportDiagnostic(
            "NO_HTTP_APIS_FOUND",
            "No HTTP interfaces found",
            "Java source files were found, but no Spring HTTP routes were detected.",
            "Add a Spring @RestController with HTTP request mappings, then rerun analysis."
        ));
        assertThat(objectMapper.writeValueAsString(response)).doesNotContain(secretSource).doesNotContain("API_KEY");
        assertFailedArtifacts(response);
        assertDetailBlocker(response.materialId(), response.blockers().getFirst());
    }

    private ProjectImportResponse importFailed(Path projectDir, String code, String summary) throws Exception {
        var content = mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "projectName", "issue05-service",
                    "storagePath", projectDir.toString(),
                    "requestedBy", "tester"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.materialType").value("SOURCE_DIRECTORY"))
            .andExpect(jsonPath("$.apiSpecCount").value(0))
            .andExpect(jsonPath("$.warnings", hasSize(0)))
            .andExpect(jsonPath("$.blockers", hasSize(1)))
            .andExpect(jsonPath("$.blockers[0].code").value(code))
            .andExpect(jsonPath("$.blockers[0].summary").value(summary))
            .andExpect(jsonPath("$.blockers[0].message").isNotEmpty())
            .andExpect(jsonPath("$.blockers[0].suggestedAction").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readValue(content, ProjectImportResponse.class);
    }

    private void assertFailedArtifacts(ProjectImportResponse response) {
        var material = sourceMaterials.findById(response.materialId()).orElseThrow();
        var task = tasks.findById(response.taskId()).orElseThrow();

        assertThat(material.getIngestStatus()).isEqualTo(IngestStatus.FAILED);
        assertThat(material.getTaskId()).isEqualTo(response.taskId());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getMetadata()).containsEntry("errorCode", response.blockers().getFirst().code());
        assertThat(apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(response.materialId())).isEmpty();
    }

    private void assertDetailBlocker(String materialId, ProjectImportDiagnostic blocker) throws Exception {
        mockMvc.perform(get("/api/project-imports/{materialId}", materialId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.apiSpecCount").value(0))
            .andExpect(jsonPath("$.warnings", hasSize(0)))
            .andExpect(jsonPath("$.blockers", hasSize(1)))
            .andExpect(jsonPath("$.blockers[0].code").value(blocker.code()))
            .andExpect(jsonPath("$.blockers[0].summary").value(blocker.summary()))
            .andExpect(jsonPath("$.blockers[0].message").value(blocker.message()))
            .andExpect(jsonPath("$.blockers[0].suggestedAction").value(blocker.suggestedAction()));
    }

    private static Path createTempRoot(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create project import test root", exception);
        }
    }
}
