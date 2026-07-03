package com.probeflow.testagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class KnowledgeRetrievalApplicationServiceTests {

    @Autowired
    private KnowledgeIngestApplicationService knowledgeIngest;

    @Autowired
    private KnowledgeRetrievalApplicationService knowledgeRetrieval;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @Test
    void returnsEmptySuccessfulResultWhenKnowledgeBaseIsEmpty() {
        var result = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "payment retry",
            "order-platform",
            "payment",
            null,
            null,
            "order",
            null,
            null,
            List.of(),
            5,
            200
        ));

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.knowledgeContext().isEmpty()).isTrue();
        assertThat(result.knowledgeContext().lowConfidence()).isTrue();
        assertThat(result.knowledgeContext().lowCoverage()).isTrue();
        assertThat(result.coverage()).isZero();
        assertThat(result.totalCandidates()).isZero();
        assertThat(result.totalTokens()).isZero();
    }

    @Test
    void retrievesOnlyLatestActiveChunksWithinStructuredScope() {
        var first = knowledgeIngest.ingest(markdownRequest(
            "Payment API guide",
            """
                # Payment route

                POST /api/orders/{orderId}/pay requires auth token for order payment.
                """,
            "wiki/payment-api-guide.md",
            DocumentType.API_NOTE,
            List.of("payment", "auth"),
            List.of("api_analysis")
        ));
        var latest = knowledgeIngest.ingest(markdownRequest(
            "Payment API guide",
            """
                # Payment route

                POST /api/orders/{orderId}/pay requires auth token and idempotency key for order payment.
                """,
            "wiki/payment-api-guide.md",
            DocumentType.API_NOTE,
            List.of("payment", "auth"),
            List.of("api_analysis")
        ));
        knowledgeIngest.ingest(markdownRequest(
            "Refund API guide",
            """
                # Refund route

                POST /api/refunds/{refundId}/submit requires payment approval.
                """,
            "wiki/refund-api-guide.md",
            DocumentType.API_NOTE,
            List.of("payment"),
            List.of("api_analysis")
        ));
        knowledgeIngest.ingest(new KnowledgeIngestRequest(
            "Incident playbook",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Failure notes

                POST /api/orders/{orderId}/pay retry guidance for ops triage.
                """,
            DocumentSourceType.WIKI,
            "wiki/payment-incident.md",
            DocumentType.INCIDENT_POSTMORTEM,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment"),
            List.of("failure_analysis"),
            Map.of()
        ));

        var result = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "payment auth",
            "order-platform",
            "payment",
            "/api/orders/{orderId}/pay",
            "post",
            "order",
            DocumentType.API_NOTE,
            "api_analysis",
            List.of("auth", "payment"),
            10,
            200
        ));

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.coverage()).isEqualTo(1.0d);
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().getFirst().documentRevisionId()).isEqualTo(latest.documentRevisionId());
        assertThat(result.hits().getFirst().documentRevisionId()).isNotEqualTo(first.documentRevisionId());
        assertThat(result.hits().getFirst().chunkContent()).contains("idempotency key");
        assertThat(result.hits().getFirst().sourceRef()).isEqualTo("wiki/payment-api-guide.md");
        assertThat(result.hits().getFirst().documentType()).isEqualTo(DocumentType.API_NOTE);
        assertThat(result.hits().getFirst().tags()).contains("auth", "payment");
        assertThat(result.hits().getFirst().applicableStages()).contains("api_analysis");
        assertThat(result.knowledgeContext().apiNotes()).hasSize(1);
        assertThat(result.knowledgeContext().apiNotes().getFirst().chunkId()).isEqualTo(result.hits().getFirst().chunkId());
        assertThat(result.knowledgeContext().apiNotes().getFirst().documentRevisionId()).isEqualTo(latest.documentRevisionId());
        assertThat(result.knowledgeContext().apiNotes().getFirst().sourceRef()).isEqualTo("wiki/payment-api-guide.md");
        assertThat(result.knowledgeContext().apiNotes().getFirst().evidenceType()).isEqualTo("api-note");
        assertThat(result.knowledgeContext().citedChunks()).hasSize(1);
        assertThat(result.knowledgeContext().lowConfidence()).isFalse();
        assertThat(result.knowledgeContext().lowCoverage()).isFalse();
    }

    @Test
    void returnsEmptyResultWhenStructuredFiltersDoNotMatch() {
        knowledgeIngest.ingest(markdownRequest(
            "Payment API guide",
            """
                # Payment route

                POST /api/orders/{orderId}/pay requires auth token for order payment.
                """,
            "wiki/payment-api-guide.md",
            DocumentType.API_NOTE,
            List.of("payment", "auth"),
            List.of("api_analysis")
        ));

        var result = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "payment auth",
            "order-platform",
            "payment",
            "/api/orders/{orderId}/refund",
            "POST",
            "order",
            DocumentType.API_NOTE,
            "api_analysis",
            List.of("auth", "payment"),
            10,
            200
        ));

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.coverage()).isZero();
    }

    @Test
    void appliesLimitAndTokenBudgetGracefully() {
        knowledgeIngest.ingest(new KnowledgeIngestRequest(
            "Payment checklist",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Prepare request

                Payment request must include tenant token and order identifier.

                # Validate response

                Payment response must include status code and gateway trace identifier.
                """,
            DocumentSourceType.MANUAL,
            "manual/payment-checklist.md",
            DocumentType.TEST_SPEC,
            DocumentAuthority.MEDIUM,
            "order-platform",
            "payment",
            "order",
            List.of("payment", "test-spec"),
            List.of("case_generation"),
            Map.of()
        ));

        var result = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "payment checklist",
            "order-platform",
            "payment",
            null,
            null,
            "order",
            DocumentType.TEST_SPEC,
            "case_generation",
            List.of("payment"),
            2,
            9
        ));

        assertThat(result.hits()).hasSize(1);
        assertThat(result.totalTokens()).isLessThanOrEqualTo(9);
    }

    @Test
    void assemblesKnowledgeContextIntoTypedGroupsWithCitations() {
        knowledgeIngest.ingest(markdownRequest(
            "Order placement rules",
            """
                # Preconditions

                Orders must be created before payment can start.
                """,
            "wiki/order-rules.md",
            DocumentType.DOMAIN_RULE,
            List.of("order"),
            List.of("case_generation")
        ));
        knowledgeIngest.ingest(markdownRequest(
            "Payment API notes",
            """
                # Endpoint

                POST /api/orders/{orderId}/pay requires an idempotency key.
                """,
            "wiki/payment-api.md",
            DocumentType.API_NOTE,
            List.of("payment"),
            List.of("api_analysis")
        ));
        knowledgeIngest.ingest(markdownRequest(
            "Payment test spec",
            """
                # Assertions

                Validate status and gateway trace id on successful payment.
                """,
            "wiki/payment-test-spec.md",
            DocumentType.TEST_SPEC,
            List.of("payment"),
            List.of("case_generation")
        ));
        knowledgeIngest.ingest(markdownRequest(
            "PAY-401 guide",
            """
                # Error codes

                PAY_401 means the auth signature is invalid.
                """,
            "wiki/payment-errors.md",
            DocumentType.ERROR_CODE_GUIDE,
            List.of("payment", "error"),
            List.of("failure_analysis")
        ));
        knowledgeIngest.ingest(markdownRequest(
            "Payment environment notes",
            """
                # Sandbox

                Sandbox requires the gateway clock to stay within 30 seconds.
                """,
            "wiki/payment-env.md",
            DocumentType.ENV_GUIDE,
            List.of("payment", "sandbox"),
            List.of("failure_analysis")
        ));
        knowledgeIngest.ingest(markdownRequest(
            "Payment incident hints",
            """
                # Failure recap

                Past failures often started with expired idempotency keys.
                """,
            "wiki/payment-incident.md",
            DocumentType.INCIDENT_POSTMORTEM,
            List.of("payment", "incident"),
            List.of("failure_analysis")
        ));

        var result = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "payment api case error sandbox failure",
            "order-platform",
            "payment",
            null,
            null,
            "order",
            null,
            null,
            List.of("payment"),
            10,
            400
        ));

        assertThat(result.knowledgeContext().businessRules()).hasSize(1);
        assertThat(result.knowledgeContext().apiNotes()).hasSize(1);
        assertThat(result.knowledgeContext().testSpecs()).hasSize(1);
        assertThat(result.knowledgeContext().errorCodeGuides()).hasSize(1);
        assertThat(result.knowledgeContext().environmentNotes()).hasSize(1);
        assertThat(result.knowledgeContext().incidentHints()).hasSize(1);
        assertThat(result.knowledgeContext().citedChunks()).hasSize(6);
        assertThat(result.knowledgeContext().citedChunks())
            .extracting(KnowledgeContextEntry::sourceRef)
            .contains(
                "wiki/order-rules.md",
                "wiki/payment-api.md",
                "wiki/payment-test-spec.md",
                "wiki/payment-errors.md",
                "wiki/payment-env.md",
                "wiki/payment-incident.md"
            );
        assertThat(result.knowledgeContext().citedChunks())
            .extracting(KnowledgeContextEntry::documentRevisionId)
            .doesNotContainNull();
    }

    @Test
    void marksLowConfidenceContextExplicitlyWhenOnlyWeakSparseMatchExists() {
        knowledgeIngest.ingest(markdownRequest(
            "Generic operations note",
            """
                # Internal note

                Operators monitor background workloads during maintenance windows.
                """,
            "wiki/ops-note.md",
            DocumentType.API_NOTE,
            List.of("ops"),
            List.of("api_analysis")
        ));

        var result = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "payment auth failure",
            "order-platform",
            "payment",
            null,
            null,
            "order",
            null,
            null,
            List.of(),
            5,
            200
        ));

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.lowConfidence()).isTrue();
        assertThat(result.knowledgeContext().lowConfidence()).isTrue();
        assertThat(result.coverage()).isLessThan(0.5d);
    }

    @Test
    void apiSpecReadinessTurnsTrueOnlyWhenUsefulContextIsFound() {
        knowledgeIngest.ingest(markdownRequest(
            "Payment API note",
            """
                # Endpoint

                POST /api/orders/{orderId}/pay requires an auth token and idempotency key.
                """,
            "wiki/payment-api-ready.md",
            DocumentType.API_NOTE,
            List.of("payment", "auth"),
            List.of("api_analysis")
        ));

        var apiSpec = apiSpecs.save(newApiSpec("/api/orders/{orderId}/pay", HttpMethod.POST));

        var result = knowledgeRetrieval.retrieveForApiSpec(apiSpec.getApiSpecId(), new KnowledgeQuery(
            "payment auth api",
            null,
            null,
            null,
            null,
            "order",
            DocumentType.API_NOTE,
            "api_analysis",
            List.of("payment", "auth"),
            5,
            200
        ));

        assertThat(result.knowledgeContext().apiNotes()).hasSize(1);
        assertThat(apiSpecs.findById(apiSpec.getApiSpecId()).orElseThrow().isKnowledgeContextReady()).isTrue();
    }

    @Test
    void apiSpecReadinessStaysFalseWhenRetrievalFindsNoUsefulContext() {
        var apiSpec = apiSpecs.save(newApiSpec("/api/orders/{orderId}/pay", HttpMethod.POST));

        var result = knowledgeRetrieval.retrieveForApiSpec(apiSpec.getApiSpecId(), new KnowledgeQuery(
            "payment auth api",
            null,
            null,
            null,
            null,
            "order",
            DocumentType.API_NOTE,
            "api_analysis",
            List.of("payment", "auth"),
            5,
            200
        ));

        assertThat(result.knowledgeContext().isEmpty()).isTrue();
        assertThat(apiSpecs.findById(apiSpec.getApiSpecId()).orElseThrow().isKnowledgeContextReady()).isFalse();
    }

    private KnowledgeIngestRequest markdownRequest(
        String title,
        String content,
        String sourceRef,
        DocumentType documentType,
        List<String> tags,
        List<String> applicableStages
    ) {
        return new KnowledgeIngestRequest(
            title,
            KnowledgeContentFormat.MARKDOWN,
            content,
            DocumentSourceType.WIKI,
            sourceRef,
            documentType,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            tags,
            applicableStages,
            Map.of()
        );
    }

    private ApiSpec newApiSpec(String path, HttpMethod httpMethod) {
        var apiSpec = new ApiSpec();
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("payment");
        apiSpec.setHttpMethod(httpMethod);
        apiSpec.setPath(path);
        apiSpec.setSummary("Pay order");
        apiSpec.setDescription("Pay an order by id.");
        apiSpec.setOperationId("payOrder");
        apiSpec.setParameters(Map.of());
        apiSpec.setConstraints(Map.of());
        apiSpec.setAuth(Map.of("required", true));
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("orders-openapi.yaml");
        apiSpec.setSourceLocation(Map.of("line", 12));
        apiSpec.setKnowledgeContextReady(false);
        apiSpec.setPresentInLatestAnalysis(true);
        return apiSpec;
    }
}
