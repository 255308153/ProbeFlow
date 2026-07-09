package com.probeflow.testagent.projectimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.sourcematerial.IngestStatus;
import com.probeflow.testagent.sourcematerial.MaterialType;
import com.probeflow.testagent.sourcematerial.SourceMaterialRepository;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
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
class LocalDirectoryProjectImportIssue02Tests {

    private static final Path ALLOWED_ROOT = createTempRoot("probeflow-project-import-issue02-allowed-");

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
    void localSpringDirectoryImportCreatesMaterialTaskApiSpecsAndReadySummary() throws Exception {
        var projectDir = springProject("orders-service");
        var submittedPath = projectDir.resolve(".").toString();

        var response = mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(localDirectoryBody("orders-service", submittedPath))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.materialType").value("SOURCE_DIRECTORY"))
            .andExpect(jsonPath("$.apiSpecCount").value(2))
            .andExpect(jsonPath("$.warnings").isArray())
            .andExpect(jsonPath("$.warnings", hasSize(0)))
            .andExpect(jsonPath("$.blockers").isArray())
            .andExpect(jsonPath("$.blockers", hasSize(0)))
            .andReturn()
            .getResponse()
            .getContentAsString();

        var importResponse = objectMapper.readValue(response, ProjectImportResponse.class);
        assertThat(importResponse.materialId()).isNotBlank();
        assertThat(importResponse.taskId()).isNotBlank();

        var material = sourceMaterials.findById(importResponse.materialId()).orElseThrow();
        assertThat(material.getMaterialType()).isEqualTo(MaterialType.SOURCE_DIRECTORY);
        assertThat(material.getOriginalName()).isEqualTo("orders-service");
        assertThat(material.getOriginalRef()).isEqualTo(submittedPath);
        assertThat(material.getStoragePath()).isEqualTo(projectDir.toRealPath().toString());
        assertThat(material.getIngestStatus()).isEqualTo(IngestStatus.READY);
        assertThat(material.getTaskId()).isEqualTo(importResponse.taskId());

        var task = tasks.findById(importResponse.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCreator()).isEqualTo("tester");
        assertThat(task.getSourceType()).isEqualTo(TaskSourceType.CODE_REPO);
        assertThat(task.getSourceRef()).isEqualTo(importResponse.materialId());
        assertThat(task.getTargetApiSpecIds()).hasSize(2);
        assertThat(task.getMetadata()).containsEntry("parserRoute", "spring-source");
        assertThat(task.getMetadata()).containsEntry("apiSpecCount", 2);

        var specs = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(importResponse.materialId());
        assertThat(specs).hasSize(2);
        assertThat(specs).extracting(spec -> spec.getApiSpecId())
            .containsExactlyInAnyOrderElementsOf(task.getTargetApiSpecIds());
        assertThat(specs).allSatisfy(spec -> {
            assertThat(spec.getSourceType()).isEqualTo(ApiSpecSourceType.CODE_ANALYSIS);
            assertThat(spec.getSourceMaterialId()).isEqualTo(importResponse.materialId());
            assertThat(spec.getModuleName()).isEqualTo("orders");
            assertThat(spec.getSummary()).startsWith("OrderController#");
            assertThat(spec.getSourceLocation())
                .containsEntry("relativePath", "src/main/java/com/example/orders/OrderController.java");
            assertThat(spec.isRouteReady()).isTrue();
            assertThat(spec.isBasicParamReady()).isTrue();
            assertThat(spec.isDtoExpanded()).isTrue();
            assertThat(spec.isValidationReady()).isTrue();
            assertThat(spec.isAuthReady()).isTrue();
            assertThat(spec.isKnowledgeContextReady()).isFalse();
        });
        assertThat(specs).anySatisfy(spec -> {
            assertThat(spec.getHttpMethod()).isEqualTo(HttpMethod.GET);
            assertThat(spec.getPath()).isEqualTo("/api/orders/{orderId}");
            assertThat(spec.getParameters()).containsKeys("path", "query");
        });
        assertThat(specs).anySatisfy(spec -> {
            assertThat(spec.getHttpMethod()).isEqualTo(HttpMethod.POST);
            assertThat(spec.getPath()).isEqualTo("/api/orders");
            assertThat(spec.getParameters()).containsKey("requestBody");
        });
    }

    @Test
    void localDirectoryAnalysisFailureReturnsBlockerAndFailedMaterialTask() throws Exception {
        var emptyProject = Files.createDirectories(ALLOWED_ROOT.resolve("empty-service"));

        var response = mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(localDirectoryBody("empty-service", emptyProject.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.materialType").value("SOURCE_DIRECTORY"))
            .andExpect(jsonPath("$.apiSpecCount").value(0))
            .andExpect(jsonPath("$.warnings", hasSize(0)))
            .andExpect(jsonPath("$.blockers", hasSize(1)))
            .andExpect(jsonPath("$.blockers[0].code").value("NO_APIS_FOUND"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        var importResponse = objectMapper.readValue(response, ProjectImportResponse.class);
        var material = sourceMaterials.findById(importResponse.materialId()).orElseThrow();
        assertThat(material.getIngestStatus()).isEqualTo(IngestStatus.FAILED);
        assertThat(material.getTaskId()).isEqualTo(importResponse.taskId());

        var task = tasks.findById(importResponse.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getMetadata()).containsEntry("errorCode", "NO_APIS_FOUND");
        assertThat(apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(importResponse.materialId())).isEmpty();
    }

    private Path springProject(String name) throws IOException {
        var projectDir = Files.createDirectories(ALLOWED_ROOT.resolve(name));
        var controllerDir = Files.createDirectories(projectDir.resolve("src/main/java/com/example/orders"));
        Files.writeString(controllerDir.resolve("OrderController.java"), """
            package com.example.orders;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RequestParam;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api/orders")
            class OrderController {

                @GetMapping("/{orderId}")
                OrderResponse getOrder(
                    @PathVariable("orderId") String orderId,
                    @RequestParam(name = "includeItems", required = false) boolean includeItems
                ) {
                    return null;
                }

                @PostMapping
                OrderResponse createOrder(@RequestBody CreateOrderRequest request) {
                    return null;
                }
            }

            class CreateOrderRequest {
                private String skuId;
            }

            class OrderResponse {
                private String orderId;
            }
            """);
        return projectDir;
    }

    private Map<String, Object> localDirectoryBody(String projectName, String storagePath) {
        return Map.of(
            "projectName", projectName,
            "storagePath", storagePath,
            "requestedBy", "tester"
        );
    }

    private static Path createTempRoot(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create project import test root", exception);
        }
    }
}
