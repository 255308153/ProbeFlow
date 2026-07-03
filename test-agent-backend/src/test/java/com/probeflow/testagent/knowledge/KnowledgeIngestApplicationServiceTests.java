package com.probeflow.testagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
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
class KnowledgeIngestApplicationServiceTests {

    @Autowired
    private KnowledgeIngestApplicationService knowledgeIngest;

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private KnowledgeDocumentRevisionRepository revisions;

    @Autowired
    private KnowledgeChunkRepository chunks;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void importsMarkdownDocumentIntoStableDocumentAndLatestRevision() {
        var request = new KnowledgeIngestRequest(
            "Order payment business rules",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Payment rules

                Orders must be in CREATED status before payment.
                """,
            DocumentSourceType.PRD,
            "docs/payment-rules.md",
            DocumentType.DOMAIN_RULE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment", "business-rule"),
            List.of("case_generation", "failure_analysis"),
            Map.of("owner", "qa")
        );

        var result = knowledgeIngest.ingest(request);

        entityManager.flush();
        entityManager.clear();

        assertThat(result.documentCreated()).isTrue();
        assertThat(result.revisionCreated()).isTrue();
        assertThat(result.idempotent()).isFalse();
        assertThat(result.documentId()).isNotBlank();
        assertThat(result.documentRevisionId()).isNotBlank();
        assertThat(result.version()).isEqualTo(1);

        var document = documents.findById(result.documentId()).orElseThrow();
        var revision = revisions.findById(result.documentRevisionId()).orElseThrow();

        assertThat(document.getTitle()).isEqualTo("Order payment business rules");
        assertThat(document.getSourceType()).isEqualTo(DocumentSourceType.PRD);
        assertThat(document.getSourceRef()).isEqualTo("docs/payment-rules.md");
        assertThat(document.getDocType()).isEqualTo(DocumentType.DOMAIN_RULE);
        assertThat(document.getAuthority()).isEqualTo(DocumentAuthority.HIGH);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(document.getSystemName()).isEqualTo("order-platform");
        assertThat(document.getModuleName()).isEqualTo("payment");
        assertThat(document.getBizEntity()).isEqualTo("order");
        assertThat(document.getRawContent()).contains("Orders must be in CREATED status");
        assertThat(document.getMetadata())
            .containsEntry("owner", "qa")
            .containsEntry("contentFormat", "MARKDOWN")
            .containsEntry("tags", List.of("business-rule", "payment"))
            .containsEntry("applicableStages", List.of("case_generation", "failure_analysis"));

        assertThat(revision.getDocumentId()).isEqualTo(document.getDocumentId());
        assertThat(revision.getVersion()).isEqualTo(1);
        assertThat(revision.isLatest()).isTrue();
        assertThat(revision.getRevisionStatus()).isEqualTo(RevisionStatus.ACTIVE);
        assertThat(revision.getSourceHash()).isNotBlank();
        assertThat(revision.getMetadata())
            .containsEntry("contentFormat", "MARKDOWN")
            .containsEntry("sourceRef", "docs/payment-rules.md")
            .containsEntry("rawContent", request.content());

        var storedChunks = chunks.findByDocumentRevisionIdOrderByChunkOrderAsc(result.documentRevisionId());
        assertThat(storedChunks).isNotEmpty();
        assertThat(storedChunks).allSatisfy(chunk -> {
            assertThat(chunk.getChunkStatus()).isEqualTo(ChunkStatus.ACTIVE);
            assertThat(chunk.getChunkTitle()).isEqualTo("Payment rules");
            assertThat(chunk.getTokenCount()).isPositive();
            assertThat(chunk.getTags()).contains("business-rule", "payment");
            assertThat(chunk.getApplicableStages()).contains("case_generation", "failure_analysis");
            assertThat(chunk.getMetadata())
                .containsEntry("contentFormat", "MARKDOWN")
                .containsEntry("headerPath", List.of("Payment rules"))
                .containsEntry("chunkKind", "PARAGRAPH");
            assertThat(chunk.getEmbedding()).containsExactly(embeddingService.embedDocument(chunk.getChunkContent()));
        });
    }

    @Test
    void reimportingUnchangedDocumentIsIdempotent() {
        var request = baseRequest("Order payment business rules", "Orders must be in CREATED status before payment.");

        var first = knowledgeIngest.ingest(request);
        var second = knowledgeIngest.ingest(request);

        entityManager.flush();
        entityManager.clear();

        assertThat(second.documentCreated()).isFalse();
        assertThat(second.revisionCreated()).isFalse();
        assertThat(second.idempotent()).isTrue();
        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(second.documentRevisionId()).isEqualTo(first.documentRevisionId());

        var document = documents.findById(first.documentId()).orElseThrow();
        var documentRevisions = revisions.findAll().stream()
            .filter(revision -> revision.getDocumentId().equals(first.documentId()))
            .toList();

        assertThat(document.getRawContent()).isEqualTo(request.content());
        assertThat(documentRevisions).hasSize(1);
        assertThat(documentRevisions.getFirst().isLatest()).isTrue();
        assertThat(documentRevisions.getFirst().getVersion()).isEqualTo(1);
    }

    @Test
    void changedContentCreatesNewRevisionAndSupersedesPreviousRevisionAndChunks() {
        var first = knowledgeIngest.ingest(baseRequest(
            "Order payment business rules",
            "Orders must be in CREATED status before payment."
        ));
        var existingChunk = new KnowledgeChunk();
        existingChunk.setDocumentId(first.documentId());
        existingChunk.setDocumentRevisionId(first.documentRevisionId());
        existingChunk.setChunkStatus(ChunkStatus.ACTIVE);
        existingChunk.setChunkTitle("Original rule");
        existingChunk.setChunkContent("Orders must be in CREATED status before payment.");
        existingChunk.setChunkOrder(1);
        existingChunk.setTags(List.of("payment"));
        existingChunk.setApplicableStages(List.of("case_generation"));
        existingChunk.setMetadata(Map.of("placeholder", true));
        existingChunk.setTokenCount(8);
        existingChunk.setEmbedding(testEmbedding());
        chunks.save(existingChunk);

        var second = knowledgeIngest.ingest(baseRequest(
            "Order payment business rules",
            "Orders must be in PAID_PENDING status before retrying payment."
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(second.documentRevisionId()).isNotEqualTo(first.documentRevisionId());
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.documentCreated()).isFalse();
        assertThat(second.revisionCreated()).isTrue();
        assertThat(second.idempotent()).isFalse();

        var document = documents.findById(first.documentId()).orElseThrow();
        var documentRevisions = revisions.findAll().stream()
            .filter(revision -> revision.getDocumentId().equals(first.documentId()))
            .toList();
        var firstRevision = documentRevisions.stream()
            .filter(revision -> revision.getDocumentRevisionId().equals(first.documentRevisionId()))
            .findFirst()
            .orElseThrow();
        var latestRevision = documentRevisions.stream()
            .filter(revision -> revision.getDocumentRevisionId().equals(second.documentRevisionId()))
            .findFirst()
            .orElseThrow();
        var storedChunk = chunks.findById(existingChunk.getChunkId()).orElseThrow();

        assertThat(document.getRawContent()).contains("PAID_PENDING");
        assertThat(documentRevisions).hasSize(2);
        assertThat(firstRevision.isLatest()).isFalse();
        assertThat(firstRevision.getRevisionStatus()).isEqualTo(RevisionStatus.SUPERSEDED);
        assertThat(latestRevision.isLatest()).isTrue();
        assertThat(latestRevision.getRevisionStatus()).isEqualTo(RevisionStatus.ACTIVE);
        assertThat(latestRevision.getVersion()).isEqualTo(2);
        assertThat(storedChunk.getChunkStatus()).isEqualTo(ChunkStatus.SUPERSEDED);
    }

    @Test
    void rejectsBlankContentWithoutCreatingRecords() {
        assertThatThrownBy(() -> knowledgeIngest.ingest(baseRequest("Order payment business rules", "   ")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("content");

        assertThat(documents.count()).isZero();
        assertThat(revisions.count()).isZero();
        assertThat(chunks.count()).isZero();
    }

    @Test
    void rejectsUnsupportedFormatWithoutCreatingRecords() {
        var request = new KnowledgeIngestRequest(
            "Order payment business rules",
            null,
            "Orders must be in CREATED status before payment.",
            DocumentSourceType.PRD,
            "docs/payment-rules.md",
            DocumentType.DOMAIN_RULE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment"),
            List.of("case_generation"),
            Map.of()
        );

        assertThatThrownBy(() -> knowledgeIngest.ingest(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("contentFormat");

        assertThat(documents.count()).isZero();
        assertThat(revisions.count()).isZero();
        assertThat(chunks.count()).isZero();
    }

    @Test
    void markdownChunkingPreservesHeadingHierarchyAndStructuredBlocks() {
        var request = new KnowledgeIngestRequest(
            "Payment integration notes",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Payment rules

                Orders must be in CREATED status before payment.

                ## Retry policy

                - Retry only when status is PROCESSING.
                - Stop after 3 attempts.

                | Error Code | Meaning |
                | --- | --- |
                | PAY_401 | Signature invalid |
                | PAY_409 | Order status invalid |

                ```bash
                curl -X POST /api/payments
                ```
                """,
            DocumentSourceType.WIKI,
            "wiki/payment-rules.md",
            DocumentType.API_NOTE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment", "auth"),
            List.of("api_analysis", "case_generation"),
            Map.of("owner", "qa")
        );

        var result = knowledgeIngest.ingest(request);

        entityManager.flush();
        entityManager.clear();

        var storedChunks = chunks.findByDocumentRevisionIdOrderByChunkOrderAsc(result.documentRevisionId());

        assertThat(storedChunks).hasSize(4);
        assertThat(storedChunks).extracting(KnowledgeChunk::getChunkOrder).containsExactly(1, 2, 3, 4);
        assertThat(storedChunks).allSatisfy(chunk -> {
            assertThat(chunk.getChunkStatus()).isEqualTo(ChunkStatus.ACTIVE);
            assertThat(chunk.getTokenCount()).isPositive();
            assertThat(chunk.getTags()).contains("auth", "payment");
            assertThat(chunk.getApplicableStages()).contains("api_analysis", "case_generation");
            assertThat(chunk.getMetadata()).containsEntry("parentChunkId", null);
        });
        assertThat(storedChunks).anySatisfy(chunk -> {
            assertThat(chunk.getChunkTitle()).isEqualTo("Payment rules");
            assertThat(chunk.getChunkContent()).contains("Orders must be in CREATED status before payment.");
            assertThat(chunk.getMetadata())
                .containsEntry("chunkKind", "PARAGRAPH")
                .containsEntry("headerPath", List.of("Payment rules"));
        });
        assertThat(storedChunks).anySatisfy(chunk -> {
            assertThat(chunk.getChunkTitle()).isEqualTo("Retry policy");
            assertThat(chunk.getChunkContent())
                .contains("- Retry only when status is PROCESSING.")
                .contains("- Stop after 3 attempts.");
            assertThat(chunk.getMetadata())
                .containsEntry("chunkKind", "LIST")
                .containsEntry("headerPath", List.of("Payment rules", "Retry policy"));
        });
        assertThat(storedChunks).anySatisfy(chunk -> {
            assertThat(chunk.getChunkTitle()).isEqualTo("Retry policy");
            assertThat(chunk.getChunkContent())
                .contains("| Error Code | Meaning |")
                .contains("| PAY_409 | Order status invalid |");
            assertThat(chunk.getMetadata())
                .containsEntry("chunkKind", "TABLE")
                .containsEntry("headerPath", List.of("Payment rules", "Retry policy"));
        });
        assertThat(storedChunks).anySatisfy(chunk -> {
            assertThat(chunk.getChunkTitle()).isEqualTo("Retry policy");
            assertThat(chunk.getChunkContent())
                .contains("```bash")
                .contains("curl -X POST /api/payments");
            assertThat(chunk.getMetadata())
                .containsEntry("chunkKind", "CODE_BLOCK")
                .containsEntry("headerPath", List.of("Payment rules", "Retry policy"));
        });
    }

    @Test
    void plainTextChunkingUsesParagraphFirstAndDeterministicFallbackOverlap() {
        var longSentence = "payment-token signature order-status amount-boundary retry-window gateway-timeout";
        var oversizedParagraph = String.join(" ", java.util.Collections.nCopies(40, longSentence));
        var request = new KnowledgeIngestRequest(
            "Plain text guide",
            KnowledgeContentFormat.PLAIN_TEXT,
            """
                Payment requests require a valid tenant and signature.

                Retry only after checking the upstream gateway status.

                """ + oversizedParagraph,
            DocumentSourceType.MANUAL,
            "notes/plain-text-guide.txt",
            DocumentType.ENV_GUIDE,
            DocumentAuthority.MEDIUM,
            "order-platform",
            "payment",
            "order",
            List.of("payment", "env"),
            List.of("api_analysis"),
            Map.of()
        );

        var result = knowledgeIngest.ingest(request);

        entityManager.flush();
        entityManager.clear();

        var storedChunks = chunks.findByDocumentRevisionIdOrderByChunkOrderAsc(result.documentRevisionId());

        assertThat(storedChunks).hasSizeGreaterThanOrEqualTo(4);
        assertThat(storedChunks.get(0).getChunkContent()).isEqualTo("Payment requests require a valid tenant and signature.");
        assertThat(storedChunks.get(1).getChunkContent()).isEqualTo("Retry only after checking the upstream gateway status.");
        assertThat(storedChunks).allSatisfy(chunk -> {
            assertThat(chunk.getChunkStatus()).isEqualTo(ChunkStatus.ACTIVE);
            assertThat(chunk.getMetadata())
                .containsEntry("chunkKind", "TEXT")
                .containsEntry("headerPath", List.of("Plain text guide"));
        });

        var fallbackChunks = storedChunks.subList(2, storedChunks.size());
        assertThat(fallbackChunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(fallbackChunks.getFirst().getTokenCount()).isLessThanOrEqualTo(140);
        assertThat(fallbackChunks.get(1).getChunkContent())
            .startsWith(lastWords(fallbackChunks.getFirst().getChunkContent(), 20));
    }

    @Test
    void metadataEnrichmentPreservesExplicitHintsAndAddsDerivedRetrievalSignals() {
        var request = new KnowledgeIngestRequest(
            "Payment failure guide",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Payment auth risk

                POST /api/orders/{orderId}/pay returns PAY_401 when signature is invalid.
                """,
            DocumentSourceType.WIKI,
            "wiki/payment-failure-guide.md",
            DocumentType.ERROR_CODE_GUIDE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("custom-tag"),
            List.of("api_analysis"),
            Map.of(
                "apiPathHints", List.of("/manual/path"),
                "httpMethodHints", List.of("PATCH"),
                "errorCodeHints", List.of("PAY_MANUAL")
            )
        );

        var result = knowledgeIngest.ingest(request);

        entityManager.flush();
        entityManager.clear();

        var storedChunks = chunks.findByDocumentRevisionIdOrderByChunkOrderAsc(result.documentRevisionId());

        assertThat(storedChunks).hasSize(1);
        assertThat(storedChunks.getFirst().getTags())
            .contains("custom-tag", "payment", "order", "auth", "risk", "error-code");
        assertThat(storedChunks.getFirst().getApplicableStages())
            .contains("api_analysis", "failure_analysis");
        assertThat(storedChunks.getFirst().getMetadata())
            .containsEntry("apiPathHints", List.of("/manual/path"))
            .containsEntry("httpMethodHints", List.of("PATCH"))
            .containsEntry("errorCodeHints", List.of("PAY_MANUAL"))
            .containsEntry("docTypeTag", "error-code")
            .containsEntry("keywordTags", List.of("auth", "error-code", "order", "payment", "risk"));
        assertThat((List<String>) storedChunks.getFirst().getMetadata().get("bizEntityHints"))
            .contains("order");
    }

    private KnowledgeIngestRequest baseRequest(String title, String content) {
        return new KnowledgeIngestRequest(
            title,
            KnowledgeContentFormat.PLAIN_TEXT,
            content,
            DocumentSourceType.PRD,
            "docs/payment-rules.md",
            DocumentType.DOMAIN_RULE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment", "business-rule"),
            List.of("case_generation"),
            new LinkedHashMap<>(Map.of("owner", "qa"))
        );
    }

    private String lastWords(String value, int count) {
        var words = value.split("\\s+");
        var start = Math.max(0, words.length - count);
        return String.join(" ", java.util.Arrays.copyOfRange(words, start, words.length));
    }

    private float[] testEmbedding() {
        var embedding = new float[1024];
        for (int index = 0; index < embedding.length; index++) {
            embedding[index] = (index + 1) / 1000.0f;
        }
        return embedding;
    }
}
