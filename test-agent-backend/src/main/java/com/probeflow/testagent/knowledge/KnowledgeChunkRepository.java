package com.probeflow.testagent.knowledge;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, String> {

    List<KnowledgeChunk> findByDocumentIdAndDocumentRevisionId(String documentId, String documentRevisionId);

    List<KnowledgeChunk> findByDocumentRevisionIdOrderByChunkOrderAsc(String documentRevisionId);
}
