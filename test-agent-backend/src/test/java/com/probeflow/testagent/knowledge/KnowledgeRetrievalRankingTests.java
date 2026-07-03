package com.probeflow.testagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class KnowledgeRetrievalRankingTests {

    @Autowired
    private KnowledgeIngestApplicationService knowledgeIngest;

    @Autowired
    private KnowledgeRetrievalApplicationService knowledgeRetrieval;

    @Test
    void keywordSignalsPromoteExactRouteMethodAndErrorMatches() {
        knowledgeIngest.ingest(request(
            "Payment failure route",
            """
                # Signature troubleshooting

                POST /api/orders/{orderId}/pay returns PAY_401 when merchantId signature is invalid. vector-alpha
                """,
            "wiki/payment-errors.md",
            DocumentType.ERROR_CODE_GUIDE,
            DocumentAuthority.HIGH,
            List.of("payment", "auth"),
            List.of("failure_analysis")
        ));
        knowledgeIngest.ingest(request(
            "Payment notes",
            """
                # Generic note

                Payment failures can happen during gateway callbacks. vector-beta
                """,
            "wiki/payment-notes.md",
            DocumentType.API_NOTE,
            DocumentAuthority.HIGH,
            List.of("payment"),
            List.of("failure_analysis")
        ));

        var result = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "PAY_401 POST /api/orders/{orderId}/pay merchantId signature",
            "order-platform",
            "payment",
            null,
            null,
            "order",
            null,
            "failure_analysis",
            List.of("payment"),
            5,
            200
        ));

        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().getFirst().sourceRef()).isEqualTo("wiki/payment-errors.md");
        assertThat(result.hits().getFirst().matchReasons()).contains("api-path", "http-method", "error-code", "field-name");
        assertThat(result.hits().getFirst().score()).isGreaterThan(result.hits().get(1).score());
    }

    @Test
    void vectorSimilarityAndStructureMatchChangeRankingOrder() {
        knowledgeIngest.ingest(request(
            "Alpha semantic note",
            """
                # Signature troubleshooting

                semantic alpha vector-alpha
                """,
            "wiki/alpha.md",
            DocumentType.API_NOTE,
            DocumentAuthority.MEDIUM,
            List.of("payment"),
            List.of("api_analysis")
        ));
        knowledgeIngest.ingest(request(
            "Beta semantic note",
            """
                # Generic heading

                semantic beta vector-beta
                """,
            "wiki/beta.md",
            DocumentType.API_NOTE,
            DocumentAuthority.MEDIUM,
            List.of("payment"),
            List.of("api_analysis")
        ));

        var result = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "semantic alpha signature troubleshooting",
            "order-platform",
            "payment",
            null,
            null,
            "order",
            null,
            "api_analysis",
            List.of("payment"),
            5,
            200
        ));

        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().getFirst().sourceRef()).isEqualTo("wiki/alpha.md");
        assertThat(result.hits().getFirst().matchReasons()).contains("semantic-match", "structure");
        assertThat(result.hits().getFirst().componentScores().get("vector"))
            .isGreaterThan(result.hits().get(1).componentScores().get("vector"));
    }

    @Test
    void authorityStageFitAndDeduplicationPreventNoisyRepeats() {
        knowledgeIngest.ingest(request(
            "Preferred checklist",
            """
                # Prepare request

                repeated setup guidance vector-shared
                """,
            "wiki/preferred-checklist.md",
            DocumentType.TEST_SPEC,
            DocumentAuthority.HIGH,
            List.of("payment", "test-spec"),
            List.of("case_generation")
        ));
        knowledgeIngest.ingest(request(
            "Duplicate checklist",
            """
                # Prepare request

                repeated setup guidance vector-shared
                """,
            "wiki/duplicate-checklist.md",
            DocumentType.TEST_SPEC,
            DocumentAuthority.LOW,
            List.of("payment", "test-spec"),
            List.of("failure_analysis")
        ));

        var result = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "case setup guidance",
            "order-platform",
            "payment",
            null,
            null,
            "order",
            DocumentType.TEST_SPEC,
            null,
            List.of("payment"),
            5,
            200
        ));

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().getFirst().sourceRef()).isEqualTo("wiki/preferred-checklist.md");
        assertThat(result.hits().getFirst().matchReasons()).contains("stage-fit");
    }

    private KnowledgeIngestRequest request(
        String title,
        String content,
        String sourceRef,
        DocumentType documentType,
        DocumentAuthority authority,
        List<String> tags,
        List<String> stages
    ) {
        return new KnowledgeIngestRequest(
            title,
            KnowledgeContentFormat.MARKDOWN,
            content,
            DocumentSourceType.WIKI,
            sourceRef,
            documentType,
            authority,
            "order-platform",
            "payment",
            "order",
            tags,
            stages,
            Map.of()
        );
    }

    @TestConfiguration
    static class RankingEmbeddingConfig {

        @Bean
        @Primary
        EmbeddingService rankingEmbeddingService() {
            return new EmbeddingService() {
                @Override
                public float[] embedDocument(String text) {
                    return vectorFor(text);
                }

                @Override
                public float[] embedQuery(String text) {
                    return vectorFor(text);
                }

                @Override
                public int dimensions() {
                    return 1024;
                }

                private float[] vectorFor(String text) {
                    var lower = text.toLowerCase();
                    var vector = new float[1024];
                    if (lower.contains("vector-alpha")) {
                        vector[0] = 1.0f;
                    }
                    if (lower.contains("vector-beta")) {
                        vector[1] = 1.0f;
                    }
                    if (lower.contains("vector-shared")) {
                        vector[2] = 1.0f;
                    }
                    if (lower.contains("semantic alpha")) {
                        vector[0] = 1.0f;
                    }
                    if (lower.contains("semantic beta")) {
                        vector[1] = 1.0f;
                    }
                    if (lower.contains("repeated setup guidance")) {
                        vector[2] = 1.0f;
                    }
                    if (vector[0] == 0.0f && vector[1] == 0.0f && vector[2] == 0.0f) {
                        vector[3] = 1.0f;
                    }
                    return vector;
                }
            };
        }
    }
}
