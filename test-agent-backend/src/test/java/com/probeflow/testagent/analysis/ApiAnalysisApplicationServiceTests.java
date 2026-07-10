package com.probeflow.testagent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.sourcematerial.IngestStatus;
import com.probeflow.testagent.sourcematerial.MaterialType;
import com.probeflow.testagent.sourcematerial.SourceMaterialRepository;
import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApiAnalysisApplicationServiceTests {

    @Autowired
    private ApiAnalysisApplicationService apiAnalysis;

    @Autowired
    private SourceMaterialRepository sourceMaterials;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @TempDir
    private Path tempDir;

    @Test
    void readableOpenApiMaterialCreatesAnalysisTaskAndRoutesParser() throws Exception {
        var openApiFile = tempDir.resolve("orders.yaml");
        Files.writeString(openApiFile, "openapi: 3.0.3\ninfo:\n  title: Orders\n  version: 1.0.0\npaths: {}\n");

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.OPENAPI_FILE,
            "orders.yaml",
            openApiFile.toString(),
            openApiFile.toString(),
            "tester"
        ));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.parserRoute()).isEqualTo("openapi");
        assertThat(result.apiSpecIds()).isEmpty();

        var material = sourceMaterials.findById(result.materialId()).orElseThrow();
        assertThat(material.getIngestStatus()).isEqualTo(IngestStatus.READY);
        assertThat(material.getTaskId()).isEqualTo(result.taskId());

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getMetadata()).containsEntry("parserRoute", "openapi");

        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(result.taskId()))
            .extracting(step -> step.getStepStatus())
            .containsOnly(PlanStepStatus.SUCCESS);
        assertThat(apiSpecs.count()).isZero();
    }

    @Test
    void openApiMaterialImportsOperationsIntoApiSpecs() throws Exception {
        var openApiFile = tempDir.resolve("orders-openapi.yaml");
        Files.writeString(openApiFile, """
            openapi: 3.0.3
            info:
              title: Order Platform
              version: 1.0.0
            components:
              securitySchemes:
                bearerAuth:
                  type: http
                  scheme: bearer
              schemas:
                CreateOrderRequest:
                  type: object
                  required:
                    - skuId
                    - quantity
                  properties:
                    skuId:
                      type: string
                    quantity:
                      type: integer
                      minimum: 1
                      maximum: 99
                    channel:
                      type: string
                      enum:
                        - WEB
                        - APP
                OrderResponse:
                  type: object
                  properties:
                    orderId:
                      type: string
                    status:
                      type: string
                      enum:
                        - CREATED
                        - PAID
            paths:
              /api/orders/{orderId}:
                get:
                  tags:
                    - orders
                  operationId: getOrder
                  summary: Get order
                  description: Load an order by id.
                  parameters:
                    - name: orderId
                      in: path
                      required: true
                      schema:
                        type: string
                    - name: includeItems
                      in: query
                      required: false
                      schema:
                        type: boolean
                    - name: X-Trace-Id
                      in: header
                      required: false
                      schema:
                        type: string
                  responses:
                    "200":
                      description: OK
                      content:
                        application/json:
                          schema:
                            $ref: "#/components/schemas/OrderResponse"
              /api/orders:
                post:
                  tags:
                    - orders
                  operationId: createOrder
                  summary: Create order
                  description: Create an order from a JSON payload.
                  security:
                    - bearerAuth: []
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          $ref: "#/components/schemas/CreateOrderRequest"
                  responses:
                    "201":
                      description: Created
                      content:
                        application/json:
                          schema:
                            $ref: "#/components/schemas/OrderResponse"
            """);

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.OPENAPI_FILE,
            "orders-openapi.yaml",
            openApiFile.toString(),
            openApiFile.toString(),
            "tester"
        ));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.apiSpecIds()).hasSize(2);

        var specs = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(result.materialId());
        assertThat(specs).hasSize(2);

        assertThat(specs).anySatisfy(spec -> {
            assertThat(spec.getSystemName()).isEqualTo("Order Platform");
            assertThat(spec.getModuleName()).isEqualTo("orders");
            assertThat(spec.getHttpMethod()).isEqualTo(HttpMethod.GET);
            assertThat(spec.getPath()).isEqualTo("/api/orders/{orderId}");
            assertThat(spec.getSummary()).isEqualTo("Get order");
            assertThat(spec.getDescription()).isEqualTo("Load an order by id.");
            assertThat(spec.getOperationId()).isEqualTo("getOrder");
            assertThat(spec.getParameters()).containsKeys("path", "query", "header", "responses");
            assertThat(spec.isRouteReady()).isTrue();
            assertThat(spec.isBasicParamReady()).isTrue();
            assertThat(spec.isDtoExpanded()).isTrue();
            assertThat(spec.isValidationReady()).isTrue();
            assertThat(spec.isAuthReady()).isTrue();
            assertThat(spec.isKnowledgeContextReady()).isFalse();
        });

        assertThat(specs).anySatisfy(spec -> {
            assertThat(spec.getHttpMethod()).isEqualTo(HttpMethod.POST);
            assertThat(spec.getPath()).isEqualTo("/api/orders");
            assertThat(spec.getSummary()).isEqualTo("Create order");
            assertThat(spec.getDescription()).isEqualTo("Create an order from a JSON payload.");
            assertThat(spec.getOperationId()).isEqualTo("createOrder");
            assertThat(spec.getParameters()).containsKeys("requestBody", "responses");
            assertThat(spec.getConstraints()).containsKeys("required", "enums");
            assertThat(spec.getAuth())
                .containsEntry("required", true)
                .containsEntry("type", "bearer")
                .containsEntry("tokenVariable", "authToken")
                .containsEntry("header", "Authorization");
            @SuppressWarnings("unchecked")
            var requestBody = (java.util.Map<String, Object>) spec.getParameters().get("requestBody");
            @SuppressWarnings("unchecked")
            var content = (java.util.List<java.util.Map<String, Object>>) requestBody.get("content");
            assertThat(content.getFirst()).containsEntry("mediaType", "application/json");
            @SuppressWarnings("unchecked")
            var schema = (java.util.Map<String, Object>) content.getFirst().get("schema");
            @SuppressWarnings("unchecked")
            var properties = (java.util.Map<String, Object>) schema.get("properties");
            @SuppressWarnings("unchecked")
            var quantity = (java.util.Map<String, Object>) properties.get("quantity");
            assertThat(((Number) quantity.get("minimum")).intValue()).isEqualTo(1);
            assertThat(((Number) quantity.get("maximum")).intValue()).isEqualTo(99);
        });

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getTargetApiSpecIds()).containsExactlyInAnyOrderElementsOf(result.apiSpecIds());
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(result.taskId()))
            .extracting(step -> step.getStepStatus())
            .containsOnly(PlanStepStatus.SUCCESS);
    }

    @Test
    void repeatedOpenApiAnalysisForSameMaterialIsIdempotent() throws Exception {
        var openApiFile = tempDir.resolve("idempotent-openapi.yaml");
        Files.writeString(openApiFile, openApiDocument("Create order", true));

        var first = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.OPENAPI_FILE,
            "idempotent-openapi.yaml",
            openApiFile.toString(),
            openApiFile.toString(),
            "tester"
        ));
        var firstSpecs = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(first.materialId());

        var second = apiAnalysis.analyze(ApiAnalysisRequest.existingMaterial(first.materialId(), "tester"));
        var secondSpecs = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(first.materialId());

        assertThat(second.succeeded()).isTrue();
        assertThat(secondSpecs).hasSize(2);
        assertThat(secondSpecs).extracting(spec -> spec.getApiSpecId())
            .containsExactlyElementsOf(firstSpecs.stream().map(spec -> spec.getApiSpecId()).toList());
        assertThat(secondSpecs).extracting(spec -> spec.getVersion()).containsOnly(1);
        assertThat(secondSpecs).extracting(spec -> spec.isPresentInLatestAnalysis()).containsOnly(true);
    }

    @Test
    void changedOpenApiOperationUpdatesExistingApiSpecVersionAndRemovedRoutePresence() throws Exception {
        var openApiFile = tempDir.resolve("changed-openapi.yaml");
        Files.writeString(openApiFile, openApiDocument("Create order", true));

        var first = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.OPENAPI_FILE,
            "changed-openapi.yaml",
            openApiFile.toString(),
            openApiFile.toString(),
            "tester"
        ));
        var createdBefore = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(first.materialId()).stream()
            .filter(spec -> spec.getOperationId().equals("createOrder"))
            .findFirst()
            .orElseThrow();

        Files.writeString(openApiFile, openApiDocument("Create order v2", false));

        var second = apiAnalysis.analyze(ApiAnalysisRequest.existingMaterial(first.materialId(), "tester"));
        var specs = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(first.materialId());

        assertThat(second.succeeded()).isTrue();
        assertThat(specs).hasSize(2);
        assertThat(specs).anySatisfy(spec -> {
            assertThat(spec.getApiSpecId()).isEqualTo(createdBefore.getApiSpecId());
            assertThat(spec.getSummary()).isEqualTo("Create order v2");
            assertThat(spec.getVersion()).isEqualTo(2);
            assertThat(spec.isPresentInLatestAnalysis()).isTrue();
        });
        assertThat(specs).anySatisfy(spec -> {
            assertThat(spec.getOperationId()).isEqualTo("getOrder");
            assertThat(spec.isPresentInLatestAnalysis()).isFalse();
        });
    }

    @Test
    void swaggerTwoInputIsExplicitlyRejectedUntilConversionIsSupported() throws Exception {
        var swaggerFile = tempDir.resolve("swagger-2.yaml");
        Files.writeString(swaggerFile, """
            swagger: "2.0"
            info:
              title: Legacy Orders
              version: "1.0"
            paths:
              /api/orders:
                get:
                  operationId: listOrders
                  responses:
                    "200":
                      description: OK
            """);

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.SWAGGER_FILE,
            "swagger-2.yaml",
            swaggerFile.toString(),
            swaggerFile.toString(),
            "tester"
        ));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.parserRoute()).isEqualTo("swagger");
        assertThat(result.errorCode()).isEqualTo("SWAGGER_UNSUPPORTED");

        var material = sourceMaterials.findById(result.materialId()).orElseThrow();
        assertThat(material.getIngestStatus()).isEqualTo(IngestStatus.FAILED);

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getMetadata()).containsEntry("errorCode", "SWAGGER_UNSUPPORTED");
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(result.taskId()))
            .extracting(step -> step.getStepStatus())
            .containsExactly(PlanStepStatus.SUCCESS, PlanStepStatus.SUCCESS, PlanStepStatus.FAILED);
        assertThat(apiSpecs.count()).isZero();
    }

    @Test
    void springSourceDirectoryImportsControllerRoutesAndBasicParameters() throws Exception {
        var sourceRoot = tempDir.resolve("spring-source");
        var controllerDir = sourceRoot.resolve("src/main/java/com/example/orders");
        Files.createDirectories(controllerDir);
        var controllerFile = controllerDir.resolve("OrderController.java");
        Files.writeString(controllerFile, """
            package com.example.orders;

            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RequestHeader;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RequestParam;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api/orders")
            class OrderController {

                @GetMapping("/{orderId}")
                ResponseEntity<OrderResponse> getOrder(
                    @PathVariable("orderId") String orderId,
                    @RequestParam(name = "includeItems", required = false) boolean includeItems,
                    @RequestHeader("X-Trace-Id") String traceId
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

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.SOURCE_DIRECTORY,
            "spring-source",
            sourceRoot.toString(),
            sourceRoot.toString(),
            "tester"
        ));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.parserRoute()).isEqualTo("spring-source");
        assertThat(result.apiSpecIds()).hasSize(2);

        var specs = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(result.materialId());
        assertThat(specs).hasSize(2);

        assertThat(specs).anySatisfy(spec -> {
            assertThat(spec.getSourceType()).isEqualTo(ApiSpecSourceType.CODE_ANALYSIS);
            assertThat(spec.getHttpMethod()).isEqualTo(HttpMethod.GET);
            assertThat(spec.getPath()).isEqualTo("/api/orders/{orderId}");
            assertThat(spec.getModuleName()).isEqualTo("orders");
            assertThat(spec.getSummary()).isEqualTo("OrderController#getOrder");
            assertThat(spec.getParameters()).containsKeys("path", "query", "header");
            assertThat(spec.getSourceLocation())
                .containsEntry("className", "OrderController")
                .containsEntry("methodName", "getOrder")
                .containsEntry("filePath", controllerFile.toString());
            assertThat(spec.getSourceLocation()).containsKey("line");
            assertThat(spec.isRouteReady()).isTrue();
            assertThat(spec.isBasicParamReady()).isTrue();
            assertThat(spec.isKnowledgeContextReady()).isFalse();
        });

        assertThat(specs).anySatisfy(spec -> {
            assertThat(spec.getHttpMethod()).isEqualTo(HttpMethod.POST);
            assertThat(spec.getPath()).isEqualTo("/api/orders");
            assertThat(spec.getParameters()).containsKey("requestBody");
            assertThat(spec.getSourceRef()).contains("OrderController#createOrder");
        });

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getMetadata()).containsEntry("parserRoute", "spring-source");
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(result.taskId()))
            .extracting(step -> step.getStepStatus())
            .containsOnly(PlanStepStatus.SUCCESS);
    }

    @Test
    void springSourceDirectoryEnrichesDtoValidationAndAuthHints() throws Exception {
        var sourceRoot = tempDir.resolve("spring-enriched-source");
        var controllerDir = sourceRoot.resolve("src/main/java/com/example/inventory");
        Files.createDirectories(controllerDir);
        Files.writeString(controllerDir.resolve("InventoryController.java"), """
            package com.example.inventory;

            import jakarta.validation.constraints.Max;
            import jakarta.validation.constraints.Min;
            import jakarta.validation.constraints.NotBlank;
            import jakarta.validation.constraints.Pattern;
            import jakarta.validation.constraints.Size;
            import org.springframework.http.ResponseEntity;
            import org.springframework.security.access.prepost.PreAuthorize;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api/inventory")
            class InventoryController {

                @PostMapping
                @PreAuthorize("hasAuthority('inventory:create')")
                ResponseEntity<CreateInventoryResponse> createInventory(@RequestBody CreateInventoryRequest request) {
                    return null;
                }
            }

            class CreateInventoryRequest {
                @NotBlank
                private String skuId;

                @Min(1)
                @Max(99)
                private int quantity;

                @Size(max = 32)
                private String channel;

                @Pattern(regexp = "^[A-Z]+$")
                private String warehouseCode;
            }

            class CreateInventoryResponse {
                private String inventoryId;
                private String status;
            }
            """);

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.SOURCE_DIRECTORY,
            "spring-enriched-source",
            sourceRoot.toString(),
            sourceRoot.toString(),
            "tester"
        ));

        assertThat(result.succeeded()).isTrue();
        var spec = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(result.materialId()).getFirst();

        @SuppressWarnings("unchecked")
        var requestBody = (java.util.Map<String, Object>) spec.getParameters().get("requestBody");
        assertThat(requestBody).containsEntry("type", "CreateInventoryRequest");
        assertThat(requestBody.get("fields").toString()).contains("skuId", "quantity", "warehouseCode");

        @SuppressWarnings("unchecked")
        var responseBody = (java.util.Map<String, Object>) spec.getParameters().get("responseBody");
        assertThat(responseBody).containsEntry("type", "CreateInventoryResponse");
        assertThat(responseBody.get("fields").toString()).contains("inventoryId", "status");

        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) spec.getConstraints().get("required");
        assertThat(required).contains("requestBody.skuId");

        @SuppressWarnings("unchecked")
        var validations = (java.util.Map<String, Object>) spec.getConstraints().get("validations");
        @SuppressWarnings("unchecked")
        var quantityValidation = (java.util.Map<String, Object>) validations.get("requestBody.quantity");
        assertThat(quantityValidation).containsEntry("minimum", 1).containsEntry("maximum", 99);
        @SuppressWarnings("unchecked")
        var channelValidation = (java.util.Map<String, Object>) validations.get("requestBody.channel");
        assertThat(channelValidation).containsEntry("maxLength", 32);
        @SuppressWarnings("unchecked")
        var warehouseValidation = (java.util.Map<String, Object>) validations.get("requestBody.warehouseCode");
        assertThat(warehouseValidation).containsEntry("pattern", "^[A-Z]+$");

        assertThat(spec.getAuth()).containsEntry("required", true);
        assertThat(spec.getAuth().toString()).contains("PreAuthorize", "inventory:create");
        assertThat(spec.isDtoExpanded()).isTrue();
        assertThat(spec.isValidationReady()).isTrue();
        assertThat(spec.isAuthReady()).isTrue();
        assertThat(spec.isKnowledgeContextReady()).isFalse();
    }

    @Test
    void sourceArchiveImportsValidSpringControllers() throws Exception {
        var archive = zipSource("valid-source.zip", Map.of(
            "src/main/java/com/example/archive/ArchiveController.java", archiveControllerSource()
        ));

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.CODE_ARCHIVE,
            "valid-source.zip",
            archive.toString(),
            archive.toString(),
            "tester"
        ));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.parserRoute()).isEqualTo("source-archive");
        assertThat(result.apiSpecIds()).hasSize(1);

        var spec = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(result.materialId()).getFirst();
        assertThat(spec.getHttpMethod()).isEqualTo(HttpMethod.GET);
        assertThat(spec.getPath()).isEqualTo("/api/archive/{itemId}");
        assertThat(spec.getSourceLocation().get("relativePath").toString())
            .contains("src/main/java/com/example/archive/ArchiveController.java");

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getMetadata()).containsEntry("parserRoute", "source-archive");
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(result.taskId()))
            .extracting(step -> step.getStepStatus())
            .containsOnly(PlanStepStatus.SUCCESS);
    }

    @Test
    void sourceArchiveKeepsValidApiSpecsWhenOneJavaFileCannotBeParsed() throws Exception {
        var archive = zipSource("partial-source.zip", Map.of(
            "src/main/java/com/example/archive/ArchiveController.java", archiveControllerSource(),
            "src/main/java/com/example/archive/BrokenController.java", "class BrokenController {"
        ));

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.CODE_ARCHIVE,
            "partial-source.zip",
            archive.toString(),
            archive.toString(),
            "tester"
        ));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.apiSpecIds()).hasSize(1);

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getMetadata()).containsEntry("warningCount", 1);
        assertThat(task.getMetadata().get("warnings").toString()).contains("BrokenController.java");
        assertThat(apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(result.materialId())).hasSize(1);
    }

    @Test
    void sourceArchiveWithUnsafeEntryFailsWithoutCreatingApiSpecs() throws Exception {
        var archive = zipSource("unsafe-source.zip", Map.of("../EscapeController.java", archiveControllerSource()));

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.CODE_ARCHIVE,
            "unsafe-source.zip",
            archive.toString(),
            archive.toString(),
            "tester"
        ));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("ARCHIVE_UNSAFE_ENTRY");
        assertThat(apiSpecs.count()).isZero();

        var material = sourceMaterials.findById(result.materialId()).orElseThrow();
        assertThat(material.getIngestStatus()).isEqualTo(IngestStatus.FAILED);

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getMetadata()).containsEntry("errorCode", "ARCHIVE_UNSAFE_ENTRY");
    }

    @Test
    void invalidMaterialPathFailsWithoutCreatingApiSpecs() {
        var missingFile = tempDir.resolve("missing.yaml");

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.OPENAPI_FILE,
            "missing.yaml",
            missingFile.toString(),
            missingFile.toString(),
            "tester"
        ));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MATERIAL_NOT_READABLE");

        var material = sourceMaterials.findById(result.materialId()).orElseThrow();
        assertThat(material.getIngestStatus()).isEqualTo(IngestStatus.FAILED);

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getMetadata()).containsEntry("errorCode", "MATERIAL_NOT_READABLE");

        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(result.taskId()))
            .extracting(step -> step.getStepStatus())
            .containsExactly(PlanStepStatus.FAILED, PlanStepStatus.SKIPPED);
        assertThat(apiSpecs.count()).isZero();
    }

    @Test
    void unsupportedMaterialTypeFailsWithClearRoutingState() throws Exception {
        var requirementFile = tempDir.resolve("requirements.md");
        Files.writeString(requirementFile, "# API notes\n");

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.REQUIREMENT_DOC,
            "requirements.md",
            requirementFile.toString(),
            requirementFile.toString(),
            "tester"
        ));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("UNSUPPORTED_MATERIAL_TYPE");

        var material = sourceMaterials.findById(result.materialId()).orElseThrow();
        assertThat(material.getIngestStatus()).isEqualTo(IngestStatus.FAILED);

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getMetadata()).containsEntry("errorCode", "UNSUPPORTED_MATERIAL_TYPE");

        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(result.taskId()))
            .extracting(step -> step.getStepStatus())
            .containsExactly(PlanStepStatus.SUCCESS, PlanStepStatus.FAILED);
        assertThat(apiSpecs.count()).isZero();
    }

    private Path zipSource(String archiveName, Map<String, String> entries) throws IOException {
        var archive = tempDir.resolve(archiveName);
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }

    private String archiveControllerSource() {
        return """
            package com.example.archive;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api/archive")
            class ArchiveController {

                @GetMapping("/{itemId}")
                ArchiveResponse getItem(@PathVariable("itemId") String itemId) {
                    return null;
                }
            }

            class ArchiveResponse {
                private String itemId;
            }
            """;
    }

    private String openApiDocument(String createOrderSummary, boolean includeGetOrder) {
        var getOrderPath = includeGetOrder
            ? "  /api/orders/{orderId}:\n"
                + "    get:\n"
                + "      tags:\n"
                + "        - orders\n"
                + "      operationId: getOrder\n"
                + "      summary: Get order\n"
                + "      parameters:\n"
                + "        - name: orderId\n"
                + "          in: path\n"
                + "          required: true\n"
                + "          schema:\n"
                + "            type: string\n"
                + "      responses:\n"
                + "        \"200\":\n"
                + "          description: OK\n"
            : "";

        return "openapi: 3.0.3\n"
            + "info:\n"
            + "  title: Order Platform\n"
            + "  version: 1.0.0\n"
            + "paths:\n"
            + getOrderPath
            + "  /api/orders:\n"
            + "    post:\n"
            + "      tags:\n"
            + "        - orders\n"
            + "      operationId: createOrder\n"
            + "      summary: " + createOrderSummary + "\n"
            + "      requestBody:\n"
            + "        required: true\n"
            + "        content:\n"
            + "          application/json:\n"
            + "            schema:\n"
            + "              type: object\n"
            + "              required:\n"
            + "                - skuId\n"
            + "              properties:\n"
            + "                skuId:\n"
            + "                  type: string\n"
            + "      responses:\n"
            + "        \"201\":\n"
            + "          description: Created\n";
    }
}
