package com.probeflow.testagent.projectimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.sourcematerial.IngestStatus;
import com.probeflow.testagent.sourcematerial.MaterialType;
import com.probeflow.testagent.sourcematerial.SourceMaterial;
import com.probeflow.testagent.sourcematerial.SourceMaterialRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
class LocalDirectoryProjectImportIssue04Tests {

    private static final Path ALLOWED_ROOT = createTempRoot("probeflow-project-import-issue04-allowed-");
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
    void reanalysisKeepsRemovedRoutesAsHistoricalAndDoesNotDuplicateUnchangedRoutes() throws Exception {
        var projectDir = springProject("orders-reanalysis");
        writeController(projectDir, controllerWithGetAndPost());

        var importResponse = importLocalDirectory("orders-reanalysis", projectDir);
        var firstSpecs = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(importResponse.materialId());
        assertThat(firstSpecs).hasSize(2);

        var firstGetSpec = findSpec(firstSpecs, HttpMethod.GET, "/api/orders/{orderId}");
        var firstPostSpec = findSpec(firstSpecs, HttpMethod.POST, "/api/orders");

        var unchangedResponse = reanalyze(importResponse.materialId(), 2);
        var unchangedSpecs = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(importResponse.materialId());
        assertThat(unchangedResponse.taskId()).isNotEqualTo(importResponse.taskId());
        assertThat(unchangedSpecs).hasSize(2);
        assertThat(unchangedSpecs).extracting(ApiSpec::getApiSpecId)
            .containsExactlyElementsOf(firstSpecs.stream().map(ApiSpec::getApiSpecId).toList());
        assertThat(unchangedSpecs).extracting(ApiSpec::getVersion).containsOnly(1);
        assertThat(unchangedSpecs).extracting(ApiSpec::isPresentInLatestAnalysis).containsOnly(true);

        writeController(projectDir, controllerWithChangedGetOnly());

        var removedRouteResponse = reanalyze(importResponse.materialId(), 1);
        assertThat(removedRouteResponse.materialId()).isEqualTo(importResponse.materialId());
        assertThat(removedRouteResponse.warnings()).isEmpty();
        assertThat(removedRouteResponse.blockers()).isEmpty();

        var specsAfterRemoval = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(importResponse.materialId());
        assertThat(specsAfterRemoval).hasSize(2);

        var currentGetSpec = findSpec(specsAfterRemoval, HttpMethod.GET, "/api/orders/{orderId}");
        assertThat(currentGetSpec.getApiSpecId()).isEqualTo(firstGetSpec.getApiSpecId());
        assertThat(currentGetSpec.getVersion()).isEqualTo(2);
        assertThat(currentGetSpec.isPresentInLatestAnalysis()).isTrue();
        assertThat(currentGetSpec.getParameters().toString()).contains("includeAudit");

        var historicalPostSpec = findSpec(specsAfterRemoval, HttpMethod.POST, "/api/orders");
        assertThat(historicalPostSpec.getApiSpecId()).isEqualTo(firstPostSpec.getApiSpecId());
        assertThat(historicalPostSpec.getVersion()).isEqualTo(1);
        assertThat(historicalPostSpec.isPresentInLatestAnalysis()).isFalse();

        var listedAfterRemoval = listApiSpecs(importResponse.materialId());
        assertThat(listedAfterRemoval).hasSize(2);
        assertThat(listedAfterRemoval).anySatisfy(spec -> {
            assertThat(spec.httpMethod()).isEqualTo("GET");
            assertThat(spec.path()).isEqualTo("/api/orders/{orderId}");
            assertThat(spec.apiSpecId()).isEqualTo(firstGetSpec.getApiSpecId());
            assertThat(spec.presentInLatestAnalysis()).isTrue();
        });
        assertThat(listedAfterRemoval).anySatisfy(spec -> {
            assertThat(spec.httpMethod()).isEqualTo("POST");
            assertThat(spec.path()).isEqualTo("/api/orders");
            assertThat(spec.apiSpecId()).isEqualTo(firstPostSpec.getApiSpecId());
            assertThat(spec.presentInLatestAnalysis()).isFalse();
        });

        writeController(projectDir, controllerWithChangedGetAndDelete());

        reanalyze(importResponse.materialId(), 2);
        var specsAfterNewRoute = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(importResponse.materialId());
        assertThat(specsAfterNewRoute).hasSize(3);
        assertThat(findSpec(specsAfterNewRoute, HttpMethod.GET, "/api/orders/{orderId}").getApiSpecId())
            .isEqualTo(firstGetSpec.getApiSpecId());
        assertThat(findSpec(specsAfterNewRoute, HttpMethod.POST, "/api/orders").isPresentInLatestAnalysis())
            .isFalse();
        var newDeleteSpec = findSpec(specsAfterNewRoute, HttpMethod.DELETE, "/api/orders/{orderId}");
        assertThat(newDeleteSpec.getApiSpecId()).isNotIn(firstGetSpec.getApiSpecId(), firstPostSpec.getApiSpecId());
        assertThat(newDeleteSpec.isPresentInLatestAnalysis()).isTrue();
    }

    @Test
    void reanalysisReturnsStructuredErrorsForMissingAndNonSourceDirectoryMaterials() throws Exception {
        mockMvc.perform(post("/api/project-imports/definitely-missing-issue04/reanalyze"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PROJECT_IMPORT_NOT_FOUND"))
            .andExpect(jsonPath("$.category").value("NOT_FOUND"))
            .andExpect(jsonPath("$.field").value("materialId"));

        var openApiFile = Files.writeString(
            Files.createTempFile(ALLOWED_ROOT, "orders-openapi-", ".yaml"),
            "openapi: 3.0.3\ninfo:\n  title: Orders\n  version: 1.0.0\npaths: {}\n"
        );
        var material = new SourceMaterial();
        material.setMaterialType(MaterialType.OPENAPI_FILE);
        material.setOriginalName("orders-openapi.yaml");
        material.setOriginalRef(openApiFile.toString());
        material.setStoragePath(openApiFile.toString());
        material.setIngestStatus(IngestStatus.READY);
        var saved = sourceMaterials.save(material);

        mockMvc.perform(post("/api/project-imports/{materialId}/reanalyze", saved.getMaterialId()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PROJECT_IMPORT_NOT_SOURCE_DIRECTORY"))
            .andExpect(jsonPath("$.category").value("BOUNDARY"))
            .andExpect(jsonPath("$.field").value("materialId"));
    }

    private ProjectImportResponse importLocalDirectory(String projectName, Path projectDir) throws Exception {
        var response = mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(localDirectoryBody(projectName, projectDir.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.materialType").value("SOURCE_DIRECTORY"))
            .andExpect(jsonPath("$.apiSpecCount").value(2))
            .andExpect(jsonPath("$.warnings", hasSize(0)))
            .andExpect(jsonPath("$.blockers", hasSize(0)))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readValue(response, ProjectImportResponse.class);
    }

    private ProjectImportResponse reanalyze(String materialId, int expectedLatestApiSpecCount) throws Exception {
        var response = mockMvc.perform(post("/api/project-imports/{materialId}/reanalyze", materialId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.materialId").value(materialId))
            .andExpect(jsonPath("$.taskId").isNotEmpty())
            .andExpect(jsonPath("$.materialType").value("SOURCE_DIRECTORY"))
            .andExpect(jsonPath("$.apiSpecCount").value(expectedLatestApiSpecCount))
            .andExpect(jsonPath("$.warnings", hasSize(0)))
            .andExpect(jsonPath("$.blockers", hasSize(0)))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readValue(response, ProjectImportResponse.class);
    }

    private List<ProjectImportApiSpecResponse> listApiSpecs(String materialId) throws Exception {
        var response = mockMvc.perform(get("/api/project-imports/{materialId}/api-specs", materialId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readValue(response, API_SPEC_LIST);
    }

    private ApiSpec findSpec(List<ApiSpec> specs, HttpMethod method, String path) {
        return specs.stream()
            .filter(spec -> spec.getHttpMethod() == method)
            .filter(spec -> spec.getPath().equals(path))
            .findFirst()
            .orElseThrow();
    }

    private Path springProject(String name) throws IOException {
        return Files.createTempDirectory(ALLOWED_ROOT, name + "-");
    }

    private void writeController(Path projectDir, String source) throws IOException {
        var controllerDir = Files.createDirectories(projectDir.resolve("src/main/java/com/example/orders"));
        Files.writeString(controllerDir.resolve("OrderController.java"), source);
    }

    private String controllerWithGetAndPost() {
        return """
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
            """;
    }

    private String controllerWithChangedGetOnly() {
        return """
            package com.example.orders;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RequestParam;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api/orders")
            class OrderController {

                @GetMapping("/{orderId}")
                OrderResponse getOrder(
                    @PathVariable("orderId") String orderId,
                    @RequestParam(name = "includeItems", required = false) boolean includeItems,
                    @RequestParam(name = "includeAudit", required = false) boolean includeAudit
                ) {
                    return null;
                }
            }

            class OrderResponse {
                private String orderId;
            }
            """;
    }

    private String controllerWithChangedGetAndDelete() {
        return """
            package com.example.orders;

            import org.springframework.web.bind.annotation.DeleteMapping;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RequestParam;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api/orders")
            class OrderController {

                @GetMapping("/{orderId}")
                OrderResponse getOrder(
                    @PathVariable("orderId") String orderId,
                    @RequestParam(name = "includeItems", required = false) boolean includeItems,
                    @RequestParam(name = "includeAudit", required = false) boolean includeAudit
                ) {
                    return null;
                }

                @DeleteMapping("/{orderId}")
                void deleteOrder(@PathVariable("orderId") String orderId) {
                }
            }

            class OrderResponse {
                private String orderId;
            }
            """;
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
