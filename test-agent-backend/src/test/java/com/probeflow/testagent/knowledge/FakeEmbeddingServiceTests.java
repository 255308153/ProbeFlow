package com.probeflow.testagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FakeEmbeddingServiceTests {

    @Autowired
    private EmbeddingService embeddingService;

    @Test
    void fakeProviderIsDeterministicForDocumentEmbeddings() {
        var first = embeddingService.embedDocument("POST /api/orders/{orderId}/pay");
        var second = embeddingService.embedDocument("POST /api/orders/{orderId}/pay");

        assertThat(first).hasSize(embeddingService.dimensions());
        assertThat(second).containsExactly(first);
    }

    @Test
    void fakeProviderSeparatesDocumentAndQueryEmbeddingPaths() {
        var documentEmbedding = embeddingService.embedDocument("payment timeout handling");
        var queryEmbedding = embeddingService.embedQuery("payment timeout handling");

        assertThat(queryEmbedding).hasSize(embeddingService.dimensions());
        assertThat(queryEmbedding).isNotEqualTo(documentEmbedding);
    }
}
