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
import com.probeflow.testagent.knowledge.KnowledgeQuery;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalApplicationService;
import com.probeflow.testagent.sourcematerial.MaterialType;
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
class Phase3AcceptanceBoundaryGuardTests {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Autowired
    private ApiAnalysisApplicationService apiAnalysis;

    @Autowired
    private KnowledgeIngestApplicationService knowledgeIngest;

    @Autowired
    private KnowledgeRetrievalApplicationService knowledgeRetrieval;

    @TempDir
    private Path tempDir;

    @Test
    void phase3AcceptanceBuildsGroundedKnowledgeContextForAnApi() throws Exception {
        var openApiFile = tempDir.resolve("phase3-orders.yaml");
        Files.writeString(openApiFile, """
            openapi: 3.0.3
            info:
              title: Phase 3 Orders
              version: 1.0.0
            paths:
              /api/orders/{orderId}/confirm:
                post:
                  operationId: confirmOrder
                  summary: Confirm order
                  responses:
                    "200":
                      description: OK
            """);

        var analysis = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.OPENAPI_FILE,
            "phase3-orders.yaml",
            openApiFile.toString(),
            openApiFile.toString(),
            "phase3-acceptance"
        ));
        var ingested = knowledgeIngest.ingest(new KnowledgeIngestRequest(
            "Order confirm operating notes",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Confirm order API

                POST /api/orders/{orderId}/confirm requires bearer auth and operatorId.

                ## Preconditions

                - Order status must already be PAID.
                - Retry only after checking the downstream gateway callback.

                ## Error codes

                - PAY_401 means the confirm signature is invalid.
                """,
            DocumentSourceType.WIKI,
            "wiki/order-confirm-notes.md",
            DocumentType.API_NOTE,
            DocumentAuthority.HIGH,
            "Order Platform",
            "orders",
            "order",
            List.of("orders", "auth"),
            List.of("api_analysis", "failure_analysis"),
            Map.of()
        ));

        var result = knowledgeRetrieval.retrieveForApiSpec(analysis.apiSpecIds().getFirst(), new KnowledgeQuery(
            "confirm order auth PAY_401",
            "Order Platform",
            "orders",
            null,
            null,
            "order",
            DocumentType.API_NOTE,
            "api_analysis",
            List.of("orders", "auth"),
            5,
            200
        ));

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().getFirst().documentRevisionId()).isEqualTo(ingested.documentRevisionId());
        assertThat(result.hits().getFirst().matchReasons()).isNotEmpty();
        assertThat(result.knowledgeContext().apiNotes()).hasSize(1);
        assertThat(result.knowledgeContext().citedChunks()).hasSize(1);
        assertThat(result.knowledgeContext().citedChunks().getFirst().sourceRef()).isEqualTo("wiki/order-confirm-notes.md");
        assertThat(result.knowledgeContext().citedChunks().getFirst().documentRevisionId()).isEqualTo(ingested.documentRevisionId());
        assertThat(result.coverage()).isGreaterThan(0.0d);
        assertThat(result.knowledgeContext().lowConfidence()).isEqualTo(result.lowConfidence());
    }

    @Test
    void phase3AcceptanceSupersedesOldChunksAfterRevisionUpdate() {
        var first = knowledgeIngest.ingest(markdownRequest(
            """
                # Confirm order API

                POST /api/orders/{orderId}/confirm requires bearer auth.
                """,
            "wiki/order-confirm-updates.md"
        ));
        var beforeUpdate = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "confirm order auth",
            "order-platform",
            "orders",
            "/api/orders/{orderId}/confirm",
            "POST",
            "order",
            DocumentType.API_NOTE,
            "api_analysis",
            List.of("orders", "auth"),
            5,
            200
        ));

        var second = knowledgeIngest.ingest(markdownRequest(
            """
                # Confirm order API

                POST /api/orders/{orderId}/confirm requires bearer auth and operatorId header.
                """,
            "wiki/order-confirm-updates.md"
        ));
        var afterUpdate = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "confirm order auth operatorId",
            "order-platform",
            "orders",
            "/api/orders/{orderId}/confirm",
            "POST",
            "order",
            DocumentType.API_NOTE,
            "api_analysis",
            List.of("orders", "auth"),
            5,
            200
        ));

        assertThat(beforeUpdate.hits()).hasSize(1);
        assertThat(beforeUpdate.hits().getFirst().documentRevisionId()).isEqualTo(first.documentRevisionId());
        assertThat(afterUpdate.hits()).hasSize(1);
        assertThat(afterUpdate.hits().getFirst().documentRevisionId()).isEqualTo(second.documentRevisionId());
        assertThat(afterUpdate.hits().getFirst().documentRevisionId()).isNotEqualTo(first.documentRevisionId());
        assertThat(afterUpdate.hits().getFirst().chunkContent()).contains("operatorId header");
    }

    @Test
    void phase3AcceptanceReturnsGracefulEmptyKnowledgeContext() {
        var result = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "confirm order auth",
            "order-platform",
            "orders",
            "/api/orders/{orderId}/confirm",
            "POST",
            "order",
            DocumentType.API_NOTE,
            "api_analysis",
            List.of("orders", "auth"),
            5,
            200
        ));

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.hits()).isEmpty();
        assertThat(result.knowledgeContext().isEmpty()).isTrue();
        assertThat(result.knowledgeContext().citedChunks()).isEmpty();
        assertThat(result.knowledgeContext().lowConfidence()).isTrue();
        assertThat(result.coverage()).isZero();
    }

    @Test
    void phase3BoundaryGuardKeepsKnowledgeRagInsideV1Boundary() throws Exception {
        var sourceRoot = PROJECT_ROOT.resolve("src/main/java");
        var applicationServices = Files.walk(sourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().endsWith("ApplicationService.java"))
            .map(path -> path.getFileName().toString().replace(".java", ""))
            .sorted()
            .toList();
        var sourceText = Files.walk(sourceRoot)
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".java"))
            .map(this::readUnchecked)
            .collect(Collectors.joining("\n"));
        var controllerAnnotations = sourceText.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("@RestController") || line.startsWith("@Controller"))
            .toList();
        var forbiddenTerms = List.of(
            "TestCaseGenerator",
            "TestCaseDraftGenerator",
            "HttpExecutionEngine",
            "WebClient",
            "RestTemplate",
            "java.net.http.HttpClient",
            "Playwright",
            "Selenium",
            "OpenAI",
            "Anthropic",
            "BrowserAutomation"
        );
        var presentForbiddenTerms = forbiddenTerms.stream()
            .filter(sourceText::contains)
            .toList();

        assertThat(applicationServices)
            .containsExactly(
                "ApiAnalysisApplicationService",
                "KnowledgeIngestApplicationService",
                "KnowledgeRetrievalApplicationService"
            );
        assertThat(controllerAnnotations).isEmpty();
        assertThat(presentForbiddenTerms).isEmpty();
    }

    private KnowledgeIngestRequest markdownRequest(String content, String sourceRef) {
        return new KnowledgeIngestRequest(
            "Order confirm operating notes",
            KnowledgeContentFormat.MARKDOWN,
            content,
            DocumentSourceType.WIKI,
            sourceRef,
            DocumentType.API_NOTE,
            DocumentAuthority.HIGH,
            "order-platform",
            "orders",
            "order",
            List.of("orders", "auth"),
            List.of("api_analysis"),
            Map.of()
        );
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
