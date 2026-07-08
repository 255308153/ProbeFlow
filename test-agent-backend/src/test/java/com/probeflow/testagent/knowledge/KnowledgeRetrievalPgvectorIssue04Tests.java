package com.probeflow.testagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.apispec.ApiSpecRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalPgvectorIssue04Tests {

    @Mock
    private KnowledgeChunkRepository chunks;

    @Mock
    private KnowledgeDocumentRepository documents;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ApiSpecRepository apiSpecs;

    @Test
    void applicationServiceUsesPgvectorCandidatesWithStructuredFiltersAndExplainableResults() {
        var profile = EmbeddingProfile.fake(1024);
        when(embeddingService.dimensions()).thenReturn(1024);
        when(embeddingService.profile()).thenReturn(profile);
        when(embeddingService.embedQuery("PAY_401 POST /api/orders/{orderId}/pay"))
            .thenReturn(vector(1.0f));

        var document = document("doc-1", "wiki/payment-errors.md");
        var chunk = chunk("chunk-1", document.getDocumentId(), "rev-2", profile);
        when(chunks.findPgvectorCandidates(
                eq("order-platform"),
                eq("payment"),
                eq("order"),
                eq(DocumentType.ERROR_CODE_GUIDE),
                eq("/api/orders/{orderId}/pay"),
                eq("POST"),
                eq("failure_analysis"),
                eq(List.of("auth", "payment")),
                any(float[].class),
                anyInt()
            ))
            .thenReturn(List.of(new KnowledgeVectorCandidate(chunk, 0.04d, 1)));
        when(documents.findAllById(any())).thenReturn(List.of(document));
        when(documents.findById(document.getDocumentId())).thenReturn(Optional.of(document));

        var result = new KnowledgeRetrievalApplicationService(chunks, documents, embeddingService, apiSpecs)
            .retrieve(new KnowledgeQuery(
                "PAY_401 POST /api/orders/{orderId}/pay",
                "order-platform",
                "payment",
                "/api/orders/{orderId}/pay",
                "post",
                "order",
                DocumentType.ERROR_CODE_GUIDE,
                "failure_analysis",
                List.of("payment", "auth"),
                3,
                120
            ));

        assertThat(result.hits()).hasSize(1);
        assertThat(result.totalCandidates()).isEqualTo(1);
        assertThat(result.totalTokens()).isLessThanOrEqualTo(120);
        assertThat(result.coverage()).isEqualTo(1.0d);

        var hit = result.hits().getFirst();
        assertThat(hit.sourceRef()).isEqualTo("wiki/payment-errors.md");
        assertThat(hit.documentRevisionId()).isEqualTo("rev-2");
        assertThat(hit.metadata())
            .containsEntry("retrievalChannel", "pgvector")
            .containsEntry("vectorDistance", 0.04d)
            .containsEntry("candidateRank", 1)
            .containsEntry(EmbeddingProfileMetadata.REINDEX_REQUIRED, false);
        assertThat(hit.metadata()).containsKey(EmbeddingProfileMetadata.EMBEDDING_PROFILE);
        assertThat(hit.componentScores())
            .containsKeys("keyword", "vector", "structure", "authority", "freshness", "stageFit");
        assertThat(hit.componentScores().get("vector")).isGreaterThan(0.0d);
        assertThat(hit.matchReasons()).contains("semantic-match", "api-path", "http-method", "error-code");
        assertThat(hit.lowConfidence()).isFalse();

        assertThat(result.knowledgeContext().citedChunks()).hasSize(1);
        var citation = result.knowledgeContext().citedChunks().getFirst();
        assertThat(citation.chunkId()).isEqualTo("chunk-1");
        assertThat(citation.documentRevisionId()).isEqualTo("rev-2");
        assertThat(citation.sourceRef()).isEqualTo("wiki/payment-errors.md");
        assertThat(citation.metadata()).containsEntry("retrievalChannel", "pgvector");

        verify(chunks, never()).findActiveLatestChunks(any(), any(), any(), any());
    }

    private KnowledgeDocument document(String documentId, String sourceRef) {
        var document = new KnowledgeDocument();
        document.setDocumentId(documentId);
        document.setTitle("Payment error guide");
        document.setSystemName("order-platform");
        document.setModuleName("payment");
        document.setBizEntity("order");
        document.setDocType(DocumentType.ERROR_CODE_GUIDE);
        document.setSourceType(DocumentSourceType.WIKI);
        document.setSourceRef(sourceRef);
        document.setAuthority(DocumentAuthority.HIGH);
        document.setStatus(DocumentStatus.ACTIVE);
        document.setMetadata(Map.of());
        document.setRawContent("PAY_401 payment error guide");
        document.onCreate();
        return document;
    }

    private KnowledgeChunk chunk(String chunkId, String documentId, String revisionId, EmbeddingProfile profile) {
        var chunk = new KnowledgeChunk();
        chunk.setChunkId(chunkId);
        chunk.setDocumentId(documentId);
        chunk.setDocumentRevisionId(revisionId);
        chunk.setChunkStatus(ChunkStatus.ACTIVE);
        chunk.setChunkTitle("PAY_401 signature failure");
        chunk.setChunkContent("POST /api/orders/{orderId}/pay returns PAY_401 when merchantId signature is invalid.");
        chunk.setChunkOrder(1);
        chunk.setTags(List.of("auth", "payment"));
        chunk.setApplicableStages(List.of("failure_analysis"));
        chunk.setMetadata(EmbeddingProfileMetadata.withProfile(Map.of(
            "apiPathHints", List.of("/api/orders/{orderId}/pay"),
            "httpMethodHints", List.of("POST"),
            "errorCodeHints", List.of("PAY_401")
        ), profile));
        chunk.setTokenCount(18);
        chunk.setEmbedding(vector(1.0f));
        return chunk;
    }

    private float[] vector(float firstValue) {
        var vector = new float[1024];
        vector[0] = firstValue;
        return vector;
    }
}
