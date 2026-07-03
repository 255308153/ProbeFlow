package com.probeflow.testagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

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
}
