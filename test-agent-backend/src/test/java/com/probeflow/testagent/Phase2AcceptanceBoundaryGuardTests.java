package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.analysis.ApiAnalysisApplicationService;
import com.probeflow.testagent.analysis.ApiAnalysisRequest;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.sourcematerial.MaterialType;
import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class Phase2AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Autowired
    private ApiAnalysisApplicationService apiAnalysis;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @TempDir
    private Path tempDir;

    @Test
    void openApiAnalysisAcceptanceIsReadyAndIdempotent() throws Exception {
        var openApiFile = tempDir.resolve("phase2-orders.yaml");
        Files.writeString(openApiFile, """
            openapi: 3.0.3
            info:
              title: Phase 2 Orders
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
                  properties:
                    skuId:
                      type: string
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
            paths:
              /api/orders:
                post:
                  tags:
                    - orders
                  operationId: createOrder
                  summary: Create order
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
              /api/orders/{orderId}:
                get:
                  tags:
                    - orders
                  operationId: getOrder
                  parameters:
                    - name: orderId
                      in: path
                      required: true
                      schema:
                        type: string
                  responses:
                    "200":
                      description: OK
            """);

        var first = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.OPENAPI_FILE,
            "phase2-orders.yaml",
            openApiFile.toString(),
            openApiFile.toString(),
            "phase2-acceptance"
        ));
        var firstSpecs = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(first.materialId());

        var second = apiAnalysis.analyze(ApiAnalysisRequest.existingMaterial(first.materialId(), "phase2-acceptance"));
        var secondSpecs = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(first.materialId());

        assertThat(first.succeeded()).isTrue();
        assertThat(second.succeeded()).isTrue();
        assertThat(firstSpecs).hasSize(2);
        assertThat(secondSpecs).hasSize(2);
        assertThat(secondSpecs).extracting(spec -> spec.getApiSpecId())
            .containsExactlyElementsOf(firstSpecs.stream().map(spec -> spec.getApiSpecId()).toList());
        assertThat(secondSpecs).allSatisfy(spec -> {
            assertThat(spec.getSourceType()).isEqualTo(ApiSpecSourceType.OPENAPI);
            assertThat(spec.isRouteReady()).isTrue();
            assertThat(spec.isBasicParamReady()).isTrue();
            assertThat(spec.isDtoExpanded()).isTrue();
            assertThat(spec.isValidationReady()).isTrue();
            assertThat(spec.isAuthReady()).isTrue();
            assertThat(spec.isKnowledgeContextReady()).isFalse();
        });

        var task = tasks.findById(second.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getMetadata()).containsEntry("parserRoute", "openapi");
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(second.taskId()))
            .extracting(step -> step.getStepStatus())
            .containsOnly(PlanStepStatus.SUCCESS);
    }

    @Test
    void springSourceAnalysisAcceptanceProducesReadyApiSpecWithoutKnowledgeContext() throws Exception {
        var sourceRoot = tempDir.resolve("phase2-spring-source");
        var controllerDir = sourceRoot.resolve("src/main/java/com/example/orders");
        Files.createDirectories(controllerDir);
        Files.writeString(controllerDir.resolve("OrderController.java"), """
            package com.example.orders;

            import jakarta.validation.constraints.NotBlank;
            import org.springframework.security.access.prepost.PreAuthorize;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api/orders")
            class OrderController {

                @PostMapping("/{orderId}/confirm")
                @PreAuthorize("hasAuthority('orders:confirm')")
                ConfirmOrderResponse confirmOrder(
                    @PathVariable("orderId") String orderId,
                    @RequestBody ConfirmOrderRequest request
                ) {
                    return null;
                }
            }

            class ConfirmOrderRequest {
                @NotBlank
                private String operatorId;
            }

            class ConfirmOrderResponse {
                private String orderId;
            }
            """);

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.SOURCE_DIRECTORY,
            "phase2-spring-source",
            sourceRoot.toString(),
            sourceRoot.toString(),
            "phase2-acceptance"
        ));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.parserRoute()).isEqualTo("spring-source");
        assertThat(result.apiSpecIds()).hasSize(1);

        var spec = apiSpecs.findBySourceMaterialIdOrderByPathAscHttpMethodAsc(result.materialId()).getFirst();
        assertThat(spec.getSourceType()).isEqualTo(ApiSpecSourceType.CODE_ANALYSIS);
        assertThat(spec.getHttpMethod()).isEqualTo(HttpMethod.POST);
        assertThat(spec.getPath()).isEqualTo("/api/orders/{orderId}/confirm");
        assertThat(spec.getParameters()).containsKeys("path", "requestBody", "responseBody");
        assertThat(spec.getConstraints().toString()).contains("requestBody.operatorId");
        assertThat(spec.getAuth()).containsEntry("required", true);
        assertThat(spec.isRouteReady()).isTrue();
        assertThat(spec.isBasicParamReady()).isTrue();
        assertThat(spec.isDtoExpanded()).isTrue();
        assertThat(spec.isValidationReady()).isTrue();
        assertThat(spec.isAuthReady()).isTrue();
        assertThat(spec.isKnowledgeContextReady()).isFalse();
    }

    @Test
    void phase2DoesNotExposeOutOfScopeApplicationServicesOrControllers() throws Exception {
        var sourceRoot = PROJECT_ROOT.resolve("src/main/java");
        var serviceClasses = Files.walk(sourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().endsWith("Service.java"))
            .map(path -> path.getFileName().toString().replace(".java", ""))
            .collect(Collectors.toList());
        var sourceText = Files.walk(sourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .filter(path -> !path.toString().contains("/demorun/"))
            .filter(path -> !path.toString().contains("/projectimport/"))
            .map(this::readUnchecked)
            .collect(Collectors.joining("\n"));
        var controllerAnnotations = sourceText.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("@RestController") || line.startsWith("@Controller"))
            .toList();
        var forbiddenTerms = List.of(
            "KnowledgeRetriever",
            "KnowledgeIngestion",
            "QueryRewriter",
            "Reranker",
            "TestCaseGenerator",
            "TestCaseDraftGenerator",
            "HttpExecutionEngine",
            "AssertionEvaluator",
            "OpenAI",
            "Anthropic",
            "ChatModel"
        );
        var presentForbiddenTerms = forbiddenTerms.stream()
            .filter(sourceText::contains)
            .toList();

        assertThat(serviceClasses).contains(
            "ApiAnalysisApplicationService",
            "EmbeddingService",
            "FakeEmbeddingService",
            "KnowledgeChunkingService",
            "KnowledgeIngestApplicationService",
            "KnowledgeRetrievalApplicationService"
        );
        assertThat(controllerAnnotations).isEmpty();
        assertThat(presentForbiddenTerms).isEmpty();
    }

    @Test
    void phase2BuildDoesNotAddRagLlmFrontendOrAutomationStacks() throws Exception {
        var pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"));

        assertThat(pom)
            .doesNotContain("langchain")
            .doesNotContain("spring-ai")
            .doesNotContain("openai")
            .doesNotContain("anthropic")
            .doesNotContain("playwright")
            .doesNotContain("selenium")
            .doesNotContain("react")
            .doesNotContain("vite")
            .doesNotContain("milvus")
            .doesNotContain("elasticsearch")
            .doesNotContain("kafka")
            .doesNotContain("neo4j");
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
