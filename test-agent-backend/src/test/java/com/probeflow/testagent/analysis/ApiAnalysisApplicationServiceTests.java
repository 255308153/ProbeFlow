package com.probeflow.testagent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.sourcematerial.IngestStatus;
import com.probeflow.testagent.sourcematerial.MaterialType;
import com.probeflow.testagent.sourcematerial.SourceMaterialRepository;
import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
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
            assertThat(spec.getAuth()).containsEntry("required", true);
        });

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getTargetApiSpecIds()).containsExactlyInAnyOrderElementsOf(result.apiSpecIds());
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(result.taskId()))
            .extracting(step -> step.getStepStatus())
            .containsOnly(PlanStepStatus.SUCCESS);
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
}
