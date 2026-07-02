package com.probeflow.testagent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.knowledge.ChunkStatus;
import com.probeflow.testagent.knowledge.DocumentAuthority;
import com.probeflow.testagent.knowledge.DocumentSourceType;
import com.probeflow.testagent.knowledge.DocumentStatus;
import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.knowledge.KnowledgeChunk;
import com.probeflow.testagent.knowledge.KnowledgeChunkRepository;
import com.probeflow.testagent.knowledge.KnowledgeDocument;
import com.probeflow.testagent.knowledge.KnowledgeDocumentRepository;
import com.probeflow.testagent.knowledge.KnowledgeDocumentRevision;
import com.probeflow.testagent.knowledge.KnowledgeDocumentRevisionRepository;
import com.probeflow.testagent.knowledge.RevisionStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
class KnowledgeDocumentChunkRepositoryTests {

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private KnowledgeDocumentRevisionRepository revisions;

    @Autowired
    private KnowledgeChunkRepository chunks;

    @Autowired
    private EntityManager entityManager;

    @Test
    void documentRevisionAndChunkCanBePersistedWithVectorEmbedding() {
        var document = new KnowledgeDocument();
        document.setTitle("Order creation API business rules");
        document.setSystemName("order-system");
        document.setModuleName("order");
        document.setDocType(DocumentType.API_NOTE);
        document.setBizEntity("order");
        document.setSourceType(DocumentSourceType.PRD);
        document.setSourceRef("docs/order-create.md");
        document.setAuthority(DocumentAuthority.HIGH);
        document.setStatus(DocumentStatus.ACTIVE);
        document.setMetadata(Map.of("owner", "qa", "tags", List.of("order", "api")));
        document.setRawContent("Creating an order requires a valid tenant and stock reservation.");
        var savedDocument = documents.save(document);

        var revision = new KnowledgeDocumentRevision();
        revision.setDocumentId(savedDocument.getDocumentId());
        revision.setVersion(3);
        revision.setLatest(true);
        revision.setRevisionStatus(RevisionStatus.ACTIVE);
        revision.setSourceHash("sha256-order-v3");
        revision.setMetadata(Map.of("updatedBy", "tester"));
        var savedRevision = revisions.save(revision);

        var chunk = new KnowledgeChunk();
        chunk.setDocumentId(savedDocument.getDocumentId());
        chunk.setDocumentRevisionId(savedRevision.getDocumentRevisionId());
        chunk.setChunkStatus(ChunkStatus.ACTIVE);
        chunk.setChunkTitle("Validation rules");
        chunk.setChunkContent("Order creation rejects missing tenantId before reserving stock.");
        chunk.setChunkOrder(1);
        chunk.setTags(List.of("validation", "tenant"));
        chunk.setApplicableStages(List.of("api_analysis", "case_generation"));
        chunk.setMetadata(Map.of("heading", "Validation rules"));
        chunk.setTokenCount(12);
        chunk.setEmbedding(testEmbedding());
        var savedChunk = chunks.save(chunk);

        entityManager.flush();
        entityManager.clear();

        var loadedDocument = documents.findById(savedDocument.getDocumentId()).orElseThrow();
        var loadedRevision = revisions.findById(savedRevision.getDocumentRevisionId()).orElseThrow();
        var loadedChunk = chunks.findById(savedChunk.getChunkId()).orElseThrow();

        assertThat(loadedDocument.getDocType()).isEqualTo(DocumentType.API_NOTE);
        assertThat(loadedDocument.getSourceType()).isEqualTo(DocumentSourceType.PRD);
        assertThat(loadedDocument.getSourceRef()).isEqualTo("docs/order-create.md");
        assertThat(loadedDocument.getMetadata()).containsEntry("owner", "qa");
        assertThat(loadedDocument.getCreatedAt()).isBeforeOrEqualTo(Instant.now());

        assertThat(loadedRevision.getDocumentId()).isEqualTo(loadedDocument.getDocumentId());
        assertThat(loadedRevision.getVersion()).isEqualTo(3);
        assertThat(loadedRevision.isLatest()).isTrue();
        assertThat(loadedRevision.getRevisionStatus()).isEqualTo(RevisionStatus.ACTIVE);

        assertThat(loadedChunk.getDocumentId()).isEqualTo(loadedDocument.getDocumentId());
        assertThat(loadedChunk.getDocumentRevisionId()).isEqualTo(loadedRevision.getDocumentRevisionId());
        assertThat(loadedChunk.getChunkStatus()).isEqualTo(ChunkStatus.ACTIVE);
        assertThat(loadedChunk.getTags()).containsExactly("validation", "tenant");
        assertThat(loadedChunk.getApplicableStages()).containsExactly("api_analysis", "case_generation");
        assertThat(loadedChunk.getEmbedding()).hasSize(1024);
        assertThat(loadedChunk.getEmbedding()[0]).isEqualTo(0.001f);
        assertThat(loadedChunk.getEmbedding()[1023]).isEqualTo(1.024f);
    }

    private float[] testEmbedding() {
        var embedding = new float[1024];
        for (int index = 0; index < embedding.length; index++) {
            embedding[index] = (index + 1) / 1000.0f;
        }
        return embedding;
    }
}
