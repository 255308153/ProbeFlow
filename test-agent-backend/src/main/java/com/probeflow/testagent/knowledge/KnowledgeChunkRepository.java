package com.probeflow.testagent.knowledge;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, String> {

    List<KnowledgeChunk> findByDocumentIdAndDocumentRevisionId(String documentId, String documentRevisionId);

    List<KnowledgeChunk> findByDocumentRevisionIdOrderByChunkOrderAsc(String documentRevisionId);

    @Query("""
        select chunk
        from KnowledgeChunk chunk, KnowledgeDocumentRevision revision, KnowledgeDocument document
        where chunk.documentRevisionId = revision.documentRevisionId
          and chunk.documentId = document.documentId
          and chunk.chunkStatus = com.probeflow.testagent.knowledge.ChunkStatus.ACTIVE
          and revision.latest = true
          and revision.revisionStatus = com.probeflow.testagent.knowledge.RevisionStatus.ACTIVE
          and document.status = com.probeflow.testagent.knowledge.DocumentStatus.ACTIVE
          and (:systemName is null or document.systemName = :systemName)
          and (:moduleName is null or document.moduleName = :moduleName)
          and (:bizEntity is null or document.bizEntity = :bizEntity)
          and (:documentType is null or document.docType = :documentType)
        order by document.updatedAt desc, chunk.chunkOrder asc
        """)
    List<KnowledgeChunk> findActiveLatestChunks(
        @Param("systemName") String systemName,
        @Param("moduleName") String moduleName,
        @Param("bizEntity") String bizEntity,
        @Param("documentType") DocumentType documentType
    );
}
