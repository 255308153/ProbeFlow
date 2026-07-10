package com.probeflow.testagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.analysis.ApiAnalysisApplicationService;
import com.probeflow.testagent.analysis.ApiAnalysisRequest;
import com.probeflow.testagent.knowledge.DocumentAuthority;
import com.probeflow.testagent.knowledge.DocumentSourceType;
import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.knowledge.KnowledgeContentFormat;
import com.probeflow.testagent.knowledge.KnowledgeIngestApplicationService;
import com.probeflow.testagent.knowledge.KnowledgeIngestRequest;
import com.probeflow.testagent.memory.LongTermMemoryQuery;
import com.probeflow.testagent.memory.LongTermMemoryRetrievalHit;
import com.probeflow.testagent.memory.LongTermMemoryRetrievalService;
import com.probeflow.testagent.memory.MemoryCandidateRequest;
import com.probeflow.testagent.memory.MemoryRefineryService;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.memory.SessionMemoryService;
import com.probeflow.testagent.memory.SessionMemoryWriteRequest;
import com.probeflow.testagent.memory.TaskMemoryService;
import com.probeflow.testagent.memory.TaskMemoryWriteRequest;
import com.probeflow.testagent.memory.UnifiedContextBuilder;
import com.probeflow.testagent.memory.UnifiedContextQuery;
import com.probeflow.testagent.sourcematerial.MaterialType;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
class Phase4AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Autowired
    private ApiAnalysisApplicationService apiAnalysis;

    @Autowired
    private KnowledgeIngestApplicationService knowledgeIngest;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TaskMemoryService taskMemoryService;

    @Autowired
    private SessionMemoryService sessionMemoryService;

    @Autowired
    private MemoryRefineryService memoryRefineryService;

    @Autowired
    private LongTermMemoryRetrievalService longTermMemoryRetrievalService;

    @Autowired
    private UnifiedContextBuilder unifiedContextBuilder;

    @TempDir
    private Path tempDir;

    @Test
    void phase4AcceptanceBuildsUnifiedContextFromKnowledgeAndMemory() throws Exception {
        var analysis = analyzeOrdersOpenApi("phase4-orders.yaml");
        var apiSpecId = analysis.apiSpecIds().getFirst();
        var task = tasks.save(newTask(apiSpecId, "phase4-acceptance"));

        sessionMemoryService.writeSessionMemory(new SessionMemoryWriteRequest(
            "phase4-acceptance-session",
            MemoryScopeType.PREFERENCE,
            "Keep failure analysis compact",
            "Prefer compact failure analysis notes while investigating payment auth issues.",
            List.of("payment", "preference"),
            MemorySourceType.USER_FEEDBACK,
            "session-phase4-acceptance",
            0.8f,
            Map.of("module", "payment"),
            3600L
        ));
        taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            task.getTaskId(),
            MemoryScopeType.FAILURE_PATTERN,
            "PAY_401 observed after skipping bootstrap",
            "Current task observed PAY_401 when tenant bootstrap was skipped before payment auth.",
            List.of("payment", "tenant", "auth"),
            MemorySourceType.OBSERVATION,
            "task-phase4-acceptance",
            0.91f,
            "failure_analysis",
            Map.of("module", "payment", "errorCode", "PAY_401"),
            null
        ));
        var knowledge = knowledgeIngest.ingest(new KnowledgeIngestRequest(
            "Payment auth rule",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Payment auth

                POST /api/orders/{orderId}/pay requires tenant bootstrap and signature validation.
                PAY_401 means the payment auth signature is invalid or bootstrap was skipped.
                """,
            DocumentSourceType.WIKI,
            "wiki/phase4-payment-auth-rule.md",
            DocumentType.API_NOTE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment", "tenant", "auth"),
            List.of("failure_analysis"),
            Map.of()
        ));
        memoryRefineryService.refine(new MemoryCandidateRequest(
            "Bootstrap tenant before payment auth",
            "Bootstrap tenant context before auth checks to avoid PAY_401 during POST /api/orders/{orderId}/pay.",
            MemorySourceType.OBSERVATION,
            "ltm-phase4-acceptance",
            task.getTaskId(),
            List.of("payment", "tenant", "auth"),
            0.94f,
            "Observed PAY_401 disappeared after tenant bootstrap was restored.",
            Map.of(
                "systemName", "order-platform",
                "module", "payment",
                "apiPath", "/api/orders/{orderId}/pay",
                "errorCode", "PAY_401"
            )
        ));

        var retrieval = longTermMemoryRetrievalService.retrieve(new LongTermMemoryQuery(
            "failure_analysis",
            "payment auth bootstrap PAY_401",
            "order-platform",
            "payment",
            "/api/orders/{orderId}/pay",
            "PAY_401",
            List.of("payment", "tenant", "auth"),
            List.of(),
            5,
            200
        ));
        var bundle = unifiedContextBuilder.build(new UnifiedContextQuery(
            task.getTaskId(),
            "phase4-acceptance-session",
            apiSpecId,
            null,
            "failure_analysis",
            "payment auth bootstrap PAY_401",
            "order-platform",
            "payment",
            "/api/orders/{orderId}/pay",
            "PAY_401",
            List.of("payment", "tenant", "auth"),
            300
        ));

        assertThat(retrieval.hits()).hasSize(1);
        assertThat(retrieval.hits().getFirst().scopeType()).isEqualTo(MemoryScopeType.FAILURE_PATTERN);
        assertThat(bundle.apiContext()).isNotNull();
        assertThat(bundle.apiContext().path()).isEqualTo("/api/orders/{orderId}/pay");
        assertThat(bundle.taskState()).isNotNull();
        assertThat(bundle.sessionContext()).extracting(memory -> memory.sourceRef())
            .containsExactly("session-phase4-acceptance");
        assertThat(bundle.taskMemory()).extracting(memory -> memory.sourceRef())
            .containsExactly("task-phase4-acceptance");
        assertThat(bundle.knowledgeContext().apiNotes()).hasSize(1);
        assertThat(bundle.longTermMemoryContext().hits()).hasSize(1);
        assertThat(bundle.longTermMemoryContext().hits()).extracting(LongTermMemoryRetrievalHit::sourceRef)
            .containsExactly("ltm-phase4-acceptance");
        assertThat(bundle.citations()).extracting(citation -> citation.sourceRef())
            .contains(
                "session-phase4-acceptance",
                "task-phase4-acceptance",
                "wiki/phase4-payment-auth-rule.md",
                "ltm-phase4-acceptance"
            );
        assertThat(bundle.citations()).extracting(citation -> citation.sourceId())
            .contains(bundle.knowledgeContext().citedChunks().getFirst().chunkId());
        assertThat(bundle.coverage().hasKnowledgeContext()).isTrue();
        assertThat(bundle.coverage().hasLongTermMemory()).isTrue();
    }

    @Test
    void phase4AcceptanceDegradesGracefullyAndMarksConflicts() throws Exception {
        var analysis = analyzeOrdersOpenApi("phase4-orders-conflict.yaml");
        var apiSpecId = analysis.apiSpecIds().getFirst();
        var task = tasks.save(newTask(apiSpecId, "phase4-conflict"));

        knowledgeIngest.ingest(new KnowledgeIngestRequest(
            "Payment bootstrap rule",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Bootstrap rule

                POST /api/orders/{orderId}/pay requires tenant bootstrap before payment auth.
                """,
            DocumentSourceType.WIKI,
            "wiki/phase4-bootstrap-rule.md",
            DocumentType.API_NOTE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment", "tenant", "auth"),
            List.of("failure_analysis"),
            Map.of()
        ));
        taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            task.getTaskId(),
            MemoryScopeType.PROJECT_KNOWLEDGE,
            "Task note says bootstrap is not required",
            "Tenant bootstrap is not required before payment auth for this observed task path.",
            List.of("payment", "tenant", "auth"),
            MemorySourceType.OBSERVATION,
            "task-phase4-conflict",
            0.85f,
            "failure_analysis",
            Map.of("module", "payment", "apiPath", "/api/orders/{orderId}/pay"),
            null
        ));

        var bundle = unifiedContextBuilder.build(new UnifiedContextQuery(
            task.getTaskId(),
            "missing-phase4-session",
            apiSpecId,
            null,
            "failure_analysis",
            "tenant bootstrap payment auth",
            "order-platform",
            "payment",
            "/api/orders/{orderId}/pay",
            null,
            List.of("payment", "tenant", "auth"),
            220
        ));

        assertThat(bundle.sessionContext()).isEmpty();
        assertThat(bundle.longTermMemoryContext().isEmpty()).isTrue();
        assertThat(bundle.coverage().hasSessionContext()).isFalse();
        assertThat(bundle.coverage().hasLongTermMemory()).isFalse();
        assertThat(bundle.conflicts()).hasSize(1);
        assertThat(bundle.conflicts().getFirst().conflictType()).isEqualTo("requirement-conflict");
        assertThat(bundle.conflicts().getFirst().knowledgeSide().sourceRef()).isEqualTo("wiki/phase4-bootstrap-rule.md");
        assertThat(bundle.conflicts().getFirst().memorySide().sourceRef()).isEqualTo("task-phase4-conflict");
    }

    @Test
    void phase4BoundaryGuardKeepsMemorySystemInsideV1Boundary() throws Exception {
        var sourceRoot = PROJECT_ROOT.resolve("src/main/java");
        var entryPoints = Files.walk(sourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> !path.toString().contains("/rerank/"))
            .filter(path -> {
                var fileName = path.getFileName().toString();
                return fileName.endsWith("Service.java")
                    || fileName.endsWith("ApplicationService.java")
                    || fileName.endsWith("Builder.java");
            })
            .map(path -> path.getFileName().toString().replace(".java", ""))
            .sorted()
            .toList();
        var sourceText = Files.walk(sourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .filter(path -> !path.toString().contains("/demorun/"))
            .filter(path -> !path.toString().contains("/rerank/"))
            .filter(path -> !path.toString().contains("/projectimport/"))
            .map(this::readUnchecked)
            .collect(Collectors.joining("\n"));
        var controllerAnnotations = sourceText.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("@RestController") || line.startsWith("@Controller"))
            .toList();
        var forbiddenTerms = List.of(
            "HttpExecutionEngine",
            "ReportRenderer",
            "Playwright",
            "Selenium",
            "BrowserAutomation",
            "WebClient",
            "RestTemplate",
            "java.net.http.HttpClient",
            "OpenAI",
            "Anthropic"
        );
        var presentForbiddenTerms = forbiddenTerms.stream()
            .filter(sourceText::contains)
            .toList();

        assertThat(entryPoints).containsExactly(
            "AgentEvaluationApplicationService",
            "AgentMemoryFeedbackApplicationService",
            "AgentPolicyService",
            "ApiAnalysisApplicationService",
            "BusinessFlowDiscoveryService",
            "ControlledPlannerService",
            "DemoRunApplicationService",
            "DeterministicQueryRewriteService",
            "EmbeddingService",
            "ExecutableRequestBuilder",
            "FailureAnalysisApplicationService",
            "FakeEmbeddingService",
            "HttpExecutionApplicationService",
            "HumanInTheLoopApplicationService",
            "KnowledgeChunkingService",
            "KnowledgeIngestApplicationService",
            "KnowledgeRetrievalApplicationService",
            "LlmApplicationService",
            "LocalDirectoryProjectImportApplicationService",
            "LongTermMemoryRetrievalService",
            "MemoryGraphProjectionService",
            "MemoryGraphQueryService",
            "MemoryRefineryService",
            "MemoryUsageRecordingService",
            "MemoryUsefulnessFeedbackService",
            "OpenApiContractSmokeRunApplicationService",
            "PlannerSafeToolCatalogService",
            "PolicyValidatorService",
            "ReplanningApplicationService",
            "ReportGenerationApplicationService",
            "SessionMemoryService",
            "SuiteDraftGenerationService",
            "TaskInitializationService",
            "TaskMemoryService",
            "TaskOrchestrationApplicationService",
            "TestCaseGenerationApplicationService",
            "TestCasePromotionService",
            "UnifiedContextBuilder",
            "VariableWriteBackService"
        );
        assertThat(controllerAnnotations).isEmpty();
        assertThat(presentForbiddenTerms).isEmpty();
        assertThat(sourceText).contains("FakeEmbeddingService");
    }

    @Test
    void phase4BuildDoesNotAddFrontendAutomationOrExternalEmbeddingStacks() throws Exception {
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
            .doesNotContain("neo4j")
            .doesNotContain("kafka")
            .doesNotContain("quartz");
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/static"))).isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/resources/templates"))).isFalse();
    }

    private com.probeflow.testagent.analysis.ApiAnalysisResult analyzeOrdersOpenApi(String fileName) throws Exception {
        var openApiFile = tempDir.resolve(fileName);
        Files.writeString(openApiFile, """
            openapi: 3.0.3
            info:
              title: Phase 4 Orders
              version: 1.0.0
            components:
              securitySchemes:
                bearerAuth:
                  type: http
                  scheme: bearer
            paths:
              /api/orders/{orderId}/pay:
                post:
                  tags:
                    - payment
                  operationId: payOrder
                  summary: Pay order
                  security:
                    - bearerAuth: []
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

        return apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.OPENAPI_FILE,
            fileName,
            openApiFile.toString(),
            openApiFile.toString(),
            "phase4-acceptance"
        ));
    }

    private Task newTask(String apiSpecId, String sourceRef) {
        var task = new Task();
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Phase 4 payment auth investigation");
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef(sourceRef);
        task.setTargetApiSpecIds(List.of(apiSpecId));
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.PENDING);
        task.setPriority(TaskPriority.HIGH);
        task.setCreator("phase4-acceptance");
        task.setMetadata(Map.of("goal", "stabilize payment auth context"));
        return task;
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
