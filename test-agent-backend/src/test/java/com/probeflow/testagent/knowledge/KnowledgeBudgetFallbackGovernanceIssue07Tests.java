package com.probeflow.testagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.retrieval.QueryFilters;
import com.probeflow.testagent.retrieval.QueryIntent;
import com.probeflow.testagent.retrieval.QueryRewriteResult;
import com.probeflow.testagent.retrieval.QueryTargetCorpus;
import com.probeflow.testagent.retrieval.QueryVariant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeBudgetFallbackGovernanceIssue07Tests {

    @Mock
    private KnowledgeChunkRepository chunks;

    @Mock
    private KnowledgeDocumentRepository documents;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ApiSpecRepository apiSpecs;

    @Test
    void allRoutesEmptyReturnLowCoverageDiagnostic() {
        var profile = EmbeddingProfile.fake(4);
        when(embeddingService.profile()).thenReturn(profile);
        when(embeddingService.dimensions()).thenReturn(4);
        when(embeddingService.embedQuery(any())).thenReturn(new float[] {0.1f, 0.2f, 0.3f, 0.4f});
        when(chunks.findPgvectorCandidates(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(List.of());
        when(chunks.findActiveLatestChunks(any(), any(), any(), any())).thenReturn(List.of());

        var result = new KnowledgeRetrievalApplicationService(chunks, documents, embeddingService, apiSpecs)
            .retrieve(query(5, 200), new QueryRewriteResult(List.of(variant()), List.of()));

        assertThat(result.hits()).isEmpty();
        assertThat(result.coverage()).isZero();
        assertThat(result.lowConfidence()).isTrue();
        assertThat(result.knowledgeContext().lowConfidence()).isTrue();
        assertThat(result.diagnostics()).contains("knowledge-low-coverage:all-routes-empty");
    }

    @Test
    void routeFailureFallbackDiagnosticIsSanitizedAndKeepsOtherRoutes() {
        var profile = EmbeddingProfile.fake(4);
        var document = document();
        var chunk = chunk(profile);
        when(embeddingService.profile()).thenReturn(profile);
        when(embeddingService.embedQuery(any())).thenThrow(new EmbeddingException(
            EmbeddingFailureCode.REMOTE_ERROR,
            profile.profileId(),
            "sensitive-marker-alpha sensitive-marker-beta sensitive-marker-gamma sensitive-marker-delta"
        ));
        when(chunks.findActiveLatestChunks("billing", "payment", "Payment", DocumentType.ERROR_CODE_GUIDE))
            .thenReturn(List.of(chunk));
        when(documents.findAllById(any())).thenReturn(List.of(document));

        var result = new KnowledgeRetrievalApplicationService(chunks, documents, embeddingService, apiSpecs)
            .retrieve(query(5, 200), new QueryRewriteResult(List.of(variant()), List.of()));

        assertThat(result.hits())
            .extracting(KnowledgeRetrievalHit::chunkId)
            .containsExactly("chunk-issue07-pay-401");
        assertThat(result.diagnostics())
            .contains("knowledge-route-failed:original-semantic", "knowledge-route-failed:rewritten-semantic");
        assertThat(String.join(" ", result.diagnostics()))
            .doesNotContain("sensitive-marker-alpha")
            .doesNotContain("sensitive-marker-beta")
            .doesNotContain("sensitive-marker-gamma")
            .doesNotContain("sensitive-marker-delta");
        assertThat(result.hits().getFirst().routeEvidence())
            .extracting(KnowledgeRouteEvidence::routeName)
            .contains("metadata-exact", "lexical-tag", "document-type")
            .doesNotContain("original-semantic", "rewritten-semantic");
    }

    @Test
    void budgetPruningDropsOversizedKnowledgeCandidateAndExplainsEmptyContext() {
        var profile = EmbeddingProfile.fake(4);
        var document = document();
        var chunk = chunk(profile);
        chunk.setTokenCount(120);
        when(embeddingService.profile()).thenReturn(profile);
        when(embeddingService.dimensions()).thenReturn(4);
        when(embeddingService.embedQuery(any())).thenReturn(new float[] {0.1f, 0.2f, 0.3f, 0.4f});
        when(chunks.findPgvectorCandidates(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(List.of());
        when(chunks.findActiveLatestChunks("billing", "payment", "Payment", DocumentType.ERROR_CODE_GUIDE))
            .thenReturn(List.of(chunk));
        when(documents.findAllById(any())).thenReturn(List.of(document));

        var result = new KnowledgeRetrievalApplicationService(chunks, documents, embeddingService, apiSpecs)
            .retrieve(query(5, 20), new QueryRewriteResult(List.of(variant()), List.of()));

        assertThat(result.hits()).isEmpty();
        assertThat(result.knowledgeContext().isEmpty()).isTrue();
        assertThat(result.totalTokens()).isZero();
        assertThat(result.diagnostics()).contains("knowledge-low-coverage:budget-pruned-all-candidates");
    }

    private static KnowledgeQuery query(int limit, int tokenBudget) {
        return new KnowledgeQuery(
            "PAY_401 charge auth failure",
            "billing",
            "payment",
            "/api/payments/charge",
            "POST",
            "Payment",
            null,
            "failure_analysis",
            List.of("payment"),
            limit,
            tokenBudget
        );
    }

    private static QueryVariant variant() {
        return new QueryVariant(
            "qv-issue07-pay-401",
            "PAY_401 /api/payments/charge merchantId",
            QueryIntent.ERROR_CODE,
            QueryTargetCorpus.KNOWLEDGE,
            "failure_analysis",
            new QueryFilters(
                "billing",
                "payment",
                "/api/payments/charge",
                "POST",
                "Payment",
                "PAY_401",
                "AUTH_FAILURE",
                null,
                null,
                null,
                null,
                List.of("payment"),
                List.of(DocumentType.ERROR_CODE_GUIDE),
                List.of()
            ),
            95,
            "Failure analysis should inspect error code guidance."
        );
    }

    private static KnowledgeDocument document() {
        var document = new KnowledgeDocument();
        document.setDocumentId("doc-issue07-pay-401");
        document.setTitle("PAY_401 guide");
        document.setSystemName("billing");
        document.setModuleName("payment");
        document.setBizEntity("Payment");
        document.setDocType(DocumentType.ERROR_CODE_GUIDE);
        document.setSourceType(DocumentSourceType.WIKI);
        document.setSourceRef("wiki/issue07-pay-401.md");
        document.setAuthority(DocumentAuthority.HIGH);
        document.setStatus(DocumentStatus.ACTIVE);
        document.setMetadata(Map.of());
        document.setRawContent("PAY_401 payment auth guidance");
        document.onCreate();
        return document;
    }

    private static KnowledgeChunk chunk(EmbeddingProfile profile) {
        var chunk = new KnowledgeChunk();
        chunk.setChunkId("chunk-issue07-pay-401");
        chunk.setDocumentId("doc-issue07-pay-401");
        chunk.setDocumentRevisionId("rev-issue07-pay-401");
        chunk.setChunkStatus(ChunkStatus.ACTIVE);
        chunk.setChunkTitle("PAY_401 charge auth");
        chunk.setChunkContent("POST /api/payments/charge returns PAY_401 when merchantId signature is invalid.");
        chunk.setChunkOrder(1);
        chunk.setTags(List.of("payment"));
        chunk.setApplicableStages(List.of("failure_analysis"));
        chunk.setMetadata(EmbeddingProfileMetadata.withProfile(Map.of(
            "apiPathHints", List.of("/api/payments/charge"),
            "httpMethodHints", List.of("POST"),
            "errorCodeHints", List.of("PAY_401"),
            "headerPath", List.of("Charge authorization failure")
        ), profile));
        chunk.setTokenCount(14);
        chunk.setEmbedding(new float[4]);
        return chunk;
    }
}
