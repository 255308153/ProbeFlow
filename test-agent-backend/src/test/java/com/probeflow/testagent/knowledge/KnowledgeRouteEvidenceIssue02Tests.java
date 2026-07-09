package com.probeflow.testagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.retrieval.DeterministicQueryRewriteService;
import com.probeflow.testagent.retrieval.QueryIntent;
import com.probeflow.testagent.retrieval.QueryRewriteRequest;
import com.probeflow.testagent.retrieval.QueryRewriteResult;
import com.probeflow.testagent.retrieval.QueryTargetCorpus;
import com.probeflow.testagent.retrieval.QueryVariant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class KnowledgeRouteEvidenceIssue02Tests {

    @Autowired
    private KnowledgeIngestApplicationService knowledgeIngest;

    @Autowired
    private KnowledgeRetrievalApplicationService knowledgeRetrieval;

    private final DeterministicQueryRewriteService rewriteService = new DeterministicQueryRewriteService();

    @Test
    void consumesKnowledgeVariantsAndKeepsEveryRouteReasonOnMergedChunk() {
        knowledgeIngest.ingest(new KnowledgeIngestRequest(
            "PAY_401 charge guide",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Charge authorization failure

                POST /api/payments/charge returns PAY_401 when merchantId signature is invalid.
                The charge request must include paymentToken and merchantId fields.
                """,
            DocumentSourceType.WIKI,
            "wiki/pay-401-charge.md",
            DocumentType.ERROR_CODE_GUIDE,
            DocumentAuthority.HIGH,
            "billing",
            "payment",
            "Payment",
            List.of("payment", "auth"),
            List.of("failure_analysis"),
            Map.of("frontmatterKeys", List.of("merchantId", "paymentToken"))
        ));

        var rewrite = rewriteService.rewrite(new QueryRewriteRequest(
            "failure_analysis",
            "charge auth failure for merchant signature",
            "explain payment charge auth failure",
            "billing",
            "payment",
            "/api/payments/charge",
            "post",
            "Payment",
            "PAY_401",
            "AUTH_FAILURE",
            null,
            null,
            null,
            null,
            List.of("payment", "auth")
        ));

        var result = knowledgeRetrieval.retrieve(new KnowledgeQuery(
            "charge auth failure for merchant signature",
            "billing",
            "payment",
            "/api/payments/charge",
            "POST",
            "Payment",
            null,
            "failure_analysis",
            List.of("payment", "auth"),
            8,
            400
        ), rewrite);

        assertThat(result.hits()).hasSize(1);
        assertThat(result.diagnostics()).doesNotContain("knowledge-route-failed:original-semantic");

        var hit = result.hits().getFirst();
        assertThat(hit.sourceRef()).isEqualTo("wiki/pay-401-charge.md");
        assertThat(hit.routeEvidence())
            .extracting(KnowledgeRouteEvidence::routeName)
            .contains(
                "original-semantic",
                "rewritten-semantic",
                "metadata-exact",
                "lexical-tag",
                "document-type"
            );
        assertThat(hit.routeEvidence())
            .allSatisfy(evidence -> {
                assertThat(evidence.queryVariantId()).startsWith("qv-");
                assertThat(evidence.routeRank()).isPositive();
                assertThat(evidence.routeScore()).isGreaterThanOrEqualTo(0.0d);
                assertThat(evidence.matchReason()).isNotBlank();
            });
        assertThat(hit.routeEvidence())
            .filteredOn(evidence -> evidence.routeName().equals("rewritten-semantic"))
            .extracting(KnowledgeRouteEvidence::queryVariantId)
            .contains(rewrite.variants().stream()
                .filter(variant -> variant.intent() == QueryIntent.ERROR_CODE)
                .findFirst()
                .orElseThrow()
                .deterministicId());
        assertThat(hit.routeEvidence())
            .extracting(KnowledgeRouteEvidence::queryVariantId)
            .doesNotContain(rewrite.variants().stream()
                .filter(variant -> variant.targetCorpus() == QueryTargetCorpus.MEMORY)
                .findFirst()
                .orElseThrow()
                .deterministicId());
        assertThat(hit.matchReasons()).contains("api-path", "http-method", "error-code", "field-name", "tag");
        assertThat(hit.metadata()).containsKey("routeEvidence");
        assertThat(result.knowledgeContext().errorCodeGuides()).hasSize(1);
    }
}

@ExtendWith(MockitoExtension.class)
class KnowledgeRouteFailureDiagnosticIssue02Tests {

    @Mock
    private KnowledgeChunkRepository chunks;

    @Mock
    private KnowledgeDocumentRepository documents;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ApiSpecRepository apiSpecs;

    @Test
    void routeFailureKeepsOtherKnowledgeCandidatesWithFallbackDiagnostic() {
        var profile = EmbeddingProfile.fake(1024);
        var document = document();
        var chunk = chunk(profile);
        when(embeddingService.profile()).thenReturn(profile);
        when(embeddingService.embedQuery(any())).thenThrow(new EmbeddingException(
            EmbeddingFailureCode.REMOTE_ERROR,
            profile.profileId(),
            "provider unavailable"
        ));
        when(chunks.findActiveLatestChunks("billing", "payment", "Payment", DocumentType.ERROR_CODE_GUIDE))
            .thenReturn(List.of(chunk));
        when(documents.findAllById(any())).thenReturn(List.of(document));

        var variant = new QueryVariant(
            "qv-failure-diagnostic",
            "PAY_401 /api/payments/charge merchantId",
            QueryIntent.ERROR_CODE,
            QueryTargetCorpus.KNOWLEDGE,
            "failure_analysis",
            new com.probeflow.testagent.retrieval.QueryFilters(
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

        var result = new KnowledgeRetrievalApplicationService(chunks, documents, embeddingService, apiSpecs)
            .retrieve(new KnowledgeQuery(
                "PAY_401 charge failure",
                "billing",
                "payment",
                "/api/payments/charge",
                "POST",
                "Payment",
                null,
                "failure_analysis",
                List.of("payment"),
                5,
                200
            ), new QueryRewriteResult(List.of(variant), List.of()));

        assertThat(result.hits()).hasSize(1);
        assertThat(result.diagnostics()).contains("knowledge-route-failed:rewritten-semantic");
        assertThat(result.hits().getFirst().routeEvidence())
            .extracting(KnowledgeRouteEvidence::routeName)
            .contains("metadata-exact", "lexical-tag", "document-type")
            .doesNotContain("rewritten-semantic");
    }

    private static KnowledgeDocument document() {
        var document = new KnowledgeDocument();
        document.setDocumentId("doc-pay-401");
        document.setTitle("PAY_401 guide");
        document.setSystemName("billing");
        document.setModuleName("payment");
        document.setBizEntity("Payment");
        document.setDocType(DocumentType.ERROR_CODE_GUIDE);
        document.setSourceType(DocumentSourceType.WIKI);
        document.setSourceRef("wiki/pay-401.md");
        document.setAuthority(DocumentAuthority.HIGH);
        document.setStatus(DocumentStatus.ACTIVE);
        document.setMetadata(Map.of());
        document.setRawContent("PAY_401 payment auth guidance");
        document.onCreate();
        return document;
    }

    private static KnowledgeChunk chunk(EmbeddingProfile profile) {
        var chunk = new KnowledgeChunk();
        chunk.setChunkId("chunk-pay-401");
        chunk.setDocumentId("doc-pay-401");
        chunk.setDocumentRevisionId("rev-pay-401");
        chunk.setChunkStatus(ChunkStatus.ACTIVE);
        chunk.setChunkTitle("PAY_401 charge auth");
        chunk.setChunkContent("POST /api/payments/charge returns PAY_401 when merchantId signature is invalid.");
        chunk.setChunkOrder(1);
        chunk.setTags(List.of("auth", "payment", "error-code"));
        chunk.setApplicableStages(List.of("failure_analysis"));
        chunk.setMetadata(EmbeddingProfileMetadata.withProfile(Map.of(
            "apiPathHints", List.of("/api/payments/charge"),
            "httpMethodHints", List.of("POST"),
            "errorCodeHints", List.of("PAY_401"),
            "headerPath", List.of("Charge authorization failure")
        ), profile));
        chunk.setTokenCount(14);
        chunk.setEmbedding(new float[1024]);
        return chunk;
    }
}
