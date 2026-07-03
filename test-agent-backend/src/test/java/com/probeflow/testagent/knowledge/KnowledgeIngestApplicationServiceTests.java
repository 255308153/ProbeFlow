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

        assertThat(chunks.findAll()).isEmpty();
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

    private float[] testEmbedding() {
        var embedding = new float[1024];
        for (int index = 0; index < embedding.length; index++) {
            embedding[index] = (index + 1) / 1000.0f;
        }
        return embedding;
    }
}
