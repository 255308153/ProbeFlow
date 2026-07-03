package com.probeflow.testagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class KnowledgeIngestEmbeddingValidationTests {

    @Autowired
    private KnowledgeIngestApplicationService knowledgeIngest;

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private KnowledgeDocumentRevisionRepository revisions;

    @Autowired
    private KnowledgeChunkRepository chunks;

    @Test
    void rejectsEmbeddingDimensionMismatchWithoutPersistingCorruptChunks() {
        var request = new KnowledgeIngestRequest(
            "Payment retry guide",
            KnowledgeContentFormat.PLAIN_TEXT,
            "Retry only after checking the gateway state.",
            DocumentSourceType.WIKI,
            "wiki/payment-retry-guide.txt",
            DocumentType.ENV_GUIDE,
            DocumentAuthority.MEDIUM,
            "order-platform",
            "payment",
            "order",
            List.of("payment"),
            List.of("failure_analysis"),
            Map.of()
        );

        assertThatThrownBy(() -> knowledgeIngest.ingest(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("embedding dimension mismatch")
            .hasMessageContaining("expected 1024")
            .hasMessageContaining("but was 8");

        assertThat(documents.count()).isZero();
        assertThat(revisions.count()).isZero();
        assertThat(chunks.count()).isZero();
    }

    @TestConfiguration
    static class InvalidEmbeddingConfig {

        @Bean
        @Primary
        EmbeddingService invalidEmbeddingService() {
            return new EmbeddingService() {
                @Override
                public float[] embedDocument(String text) {
                    return new float[8];
                }

                @Override
                public float[] embedQuery(String text) {
                    return new float[8];
                }

                @Override
                public int dimensions() {
                    return 8;
                }
            };
        }
    }
}
