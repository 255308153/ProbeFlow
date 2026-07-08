package com.probeflow.testagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void fakeProviderExposesDefaultEmbeddingProfileContract() {
        var profile = embeddingService.profile();

        assertThat(profile.providerMode()).isEqualTo(EmbeddingProviderMode.FAKE);
        assertThat(profile.profileId()).isEqualTo("fake-default");
        assertThat(profile.model()).isEqualTo("deterministic-sha256-v1");
        assertThat(profile.dimension()).isEqualTo(embeddingService.dimensions());
        assertThat(profile.configSource()).isEqualTo("application-default");
        assertThat(profile.queryPrefix()).isEqualTo("Represent this sentence for searching relevant passages: ");
        assertThat(profile.documentPrefix()).isEmpty();
    }

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

    @Test
    void fakeProviderRejectsBlankInputWithDomainError() {
        assertThatThrownBy(() -> embeddingService.embedDocument(" "))
            .isInstanceOf(EmbeddingException.class)
            .hasMessageContaining("document embedding text must not be blank")
            .extracting("failureCode")
            .isEqualTo(EmbeddingFailureCode.EMPTY_TEXT);

        assertThatThrownBy(() -> embeddingService.embedQuery(null))
            .isInstanceOf(EmbeddingException.class)
            .hasMessageContaining("query embedding text must not be blank")
            .extracting("failureCode")
            .isEqualTo(EmbeddingFailureCode.EMPTY_TEXT);
    }
}
