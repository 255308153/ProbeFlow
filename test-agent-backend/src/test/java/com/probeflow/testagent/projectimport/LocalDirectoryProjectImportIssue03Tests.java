package com.probeflow.testagent.projectimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.sourcematerial.IngestStatus;
import com.probeflow.testagent.sourcematerial.MaterialType;
import com.probeflow.testagent.sourcematerial.SourceMaterial;
import com.probeflow.testagent.sourcematerial.SourceMaterialRepository;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LocalDirectoryProjectImportIssue03Tests {

    @Mock
    private SourceMaterialRepository sourceMaterials;

    @Mock
    private ApiSpecRepository apiSpecs;

    @Mock
    private TaskRepository tasks;

    private LocalDirectoryProjectImportApplicationService service;

    @BeforeEach
    void setUp() {
        service = new LocalDirectoryProjectImportApplicationService(
            new LocalDirectoryPathValidator(List.of()),
            null,
            sourceMaterials,
            tasks,
            apiSpecs
        );
    }

    @Test
    void localDirectoryImportDetailCanBeQueriedByMaterialId() {
        var analyzedAt = Instant.parse("2026-07-09T12:00:00Z");
        var material = sourceDirectoryMaterial(
            "material-1",
            "orders-detail-service",
            "/imports/orders-detail-service",
            "/canonical/imports/orders-detail-service",
            "task-1"
        );
        var task = task("task-1", analyzedAt);
        when(sourceMaterials.findById("material-1")).thenReturn(Optional.of(material));
        when(tasks.findById("task-1")).thenReturn(Optional.of(task));
        when(apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc("material-1")).thenReturn(List.of(
            apiSpec("spec-post", "material-1", HttpMethod.POST, "/api/orders"),
            apiSpec("spec-get", "material-1", HttpMethod.GET, "/api/orders/{orderId}")
        ));

        var detail = service.getImportDetail("material-1");

        assertThat(detail.materialId()).isEqualTo("material-1");
        assertThat(detail.materialType()).isEqualTo("SOURCE_DIRECTORY");
        assertThat(detail.projectName()).isEqualTo("orders-detail-service");
        assertThat(detail.originalRef()).isEqualTo("/imports/orders-detail-service");
        assertThat(detail.storagePath()).isEqualTo("/canonical/imports/orders-detail-service");
        assertThat(detail.status()).isEqualTo("READY");
        assertThat(detail.taskId()).isEqualTo("task-1");
        assertThat(detail.apiSpecCount()).isEqualTo(2);
        assertThat(detail.lastAnalyzedAt()).isEqualTo(analyzedAt);
        assertThat(detail.warnings()).isEmpty();
        assertThat(detail.blockers()).isEmpty();
    }

    @Test
    void missingMaterialIdReturnsStructuredNotFoundError() {
        when(sourceMaterials.findById("missing-material-id")).thenReturn(Optional.empty());

        var response = new ProjectImportController(service).getImportDetail("missing-material-id");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(new ProjectImportErrorResponse(
            "PROJECT_IMPORT_NOT_FOUND",
            "NOT_FOUND",
            "materialId",
            "Project import was not found."
        ));
    }

    @Test
    void nonSourceDirectoryMaterialReturnsExplicitBoundaryError() {
        var material = material(
            "material-openapi",
            MaterialType.OPENAPI_FILE,
            "orders-openapi",
            "/imports/orders-openapi.yaml",
            "/canonical/imports/orders-openapi.yaml",
            "task-openapi",
            IngestStatus.READY
        );
        when(sourceMaterials.findById("material-openapi")).thenReturn(Optional.of(material));

        var detailResponse = new ProjectImportController(service).getImportDetail("material-openapi");
        var apiSpecResponse = new ProjectImportController(service).listApiSpecs("material-openapi");

        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(detailResponse.getBody()).isEqualTo(new ProjectImportErrorResponse(
            "PROJECT_IMPORT_NOT_SOURCE_DIRECTORY",
            "BOUNDARY",
            "materialId",
            "Input material is not a local source directory import."
        ));
        assertThat(apiSpecResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(apiSpecResponse.getBody()).isEqualTo(detailResponse.getBody());
    }

    @Test
    void apiSpecListIsStableSortedAndDoesNotReturnSourceBody() {
        var material = sourceDirectoryMaterial(
            "material-2",
            "orders-api-spec-list-service",
            "/imports/orders-api-spec-list-service",
            "/canonical/imports/orders-api-spec-list-service",
            "task-2"
        );
        var postSpec = apiSpec("spec-post", HttpMethod.POST, "/api/orders");
        var sourceLocation = new LinkedHashMap<String, Object>();
        sourceLocation.put("relativePath", "src/main/java/com/example/orders/OrderController.java");
        sourceLocation.put("sourceCode", "class OrderController { private String skuId; }");
        sourceLocation.put("nested", Map.of("snippet", "private String orderId;", "line", 12));
        postSpec.setSourceLocation(sourceLocation);

        when(sourceMaterials.findById("material-2")).thenReturn(Optional.of(material));
        when(apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc("material-2")).thenReturn(List.of(
            apiSpec("spec-get-one", "material-2", HttpMethod.GET, "/api/orders/{orderId}"),
            postSpec,
            apiSpec("spec-get-list", "material-2", HttpMethod.GET, "/api/orders")
        ));

        var apiSpecResponses = service.listApiSpecs("material-2");

        assertThat(apiSpecResponses).hasSize(3);
        assertThat(apiSpecResponses).extracting(ProjectImportApiSpecResponse::path)
            .containsExactly("/api/orders", "/api/orders", "/api/orders/{orderId}");
        assertThat(apiSpecResponses).extracting(ProjectImportApiSpecResponse::httpMethod)
            .containsExactly("GET", "POST", "GET");

        var post = apiSpecResponses.get(1);
        assertThat(post.apiSpecId()).isEqualTo("spec-post");
        assertThat(post.path()).isEqualTo("/api/orders");
        assertThat(post.moduleName()).isEqualTo("orders");
        assertThat(post.summary()).isEqualTo("OrderController#createOrder");
        assertThat(post.sourceMaterialId()).isEqualTo("material-2");
        assertThat(post.sourceLocation())
            .containsEntry("relativePath", "src/main/java/com/example/orders/OrderController.java")
            .doesNotContainKeys("sourceCode");
        assertThat(post.routeReady()).isTrue();
        assertThat(post.basicParamReady()).isTrue();
        assertThat(post.dtoExpanded()).isTrue();
        assertThat(post.validationReady()).isTrue();
        assertThat(post.authReady()).isTrue();
        assertThat(post.knowledgeContextReady()).isFalse();
        assertThat(post.presentInLatestAnalysis()).isTrue();

        assertThat(post.sourceLocation().get("nested"))
            .isInstanceOfSatisfying(Map.class, nested -> assertThat(nested)
                .containsEntry("line", 12)
                .doesNotContainKeys("snippet"));
        assertThat(apiSpecResponses.toString())
            .doesNotContain("class OrderController")
            .doesNotContain("private String skuId")
            .doesNotContain("private String orderId");
    }

    private SourceMaterial sourceDirectoryMaterial(
        String materialId,
        String projectName,
        String originalRef,
        String storagePath,
        String taskId
    ) {
        return material(
            materialId,
            MaterialType.SOURCE_DIRECTORY,
            projectName,
            originalRef,
            storagePath,
            taskId,
            IngestStatus.READY
        );
    }

    private SourceMaterial material(
        String materialId,
        MaterialType materialType,
        String originalName,
        String originalRef,
        String storagePath,
        String taskId,
        IngestStatus status
    ) {
        var material = new SourceMaterial();
        material.setMaterialId(materialId);
        material.setMaterialType(materialType);
        material.setOriginalName(originalName);
        material.setOriginalRef(originalRef);
        material.setStoragePath(storagePath);
        material.setTaskId(taskId);
        material.setIngestStatus(status);
        ReflectionTestUtils.setField(material, "updatedAt", Instant.parse("2026-07-09T11:00:00Z"));
        return material;
    }

    private Task task(String taskId, Instant updatedAt) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setStatus(TaskStatus.COMPLETED);
        task.setMetadata(Map.of());
        ReflectionTestUtils.setField(task, "updatedAt", updatedAt);
        return task;
    }

    private ApiSpec apiSpec(String apiSpecId, HttpMethod method, String path) {
        return apiSpec(apiSpecId, "material-2", method, path);
    }

    private ApiSpec apiSpec(String apiSpecId, String sourceMaterialId, HttpMethod method, String path) {
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId(apiSpecId);
        apiSpec.setHttpMethod(method);
        apiSpec.setPath(path);
        apiSpec.setModuleName("orders");
        apiSpec.setSummary(method == HttpMethod.POST ? "OrderController#createOrder" : "OrderController#getOrder");
        apiSpec.setSourceMaterialId(sourceMaterialId);
        apiSpec.setSourceType(ApiSpecSourceType.CODE_ANALYSIS);
        apiSpec.setSourceLocation(Map.of("relativePath", "src/main/java/com/example/orders/OrderController.java"));
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(false);
        apiSpec.setPresentInLatestAnalysis(true);
        return apiSpec;
    }
}
