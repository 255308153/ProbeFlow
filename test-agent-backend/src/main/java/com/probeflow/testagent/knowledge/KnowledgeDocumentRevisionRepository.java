package com.probeflow.testagent.knowledge;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocumentRevisionRepository extends JpaRepository<KnowledgeDocumentRevision, String> {

    Optional<KnowledgeDocumentRevision> findByDocumentIdAndLatestTrue(String documentId);

    List<KnowledgeDocumentRevision> findByDocumentIdOrderByVersionAsc(String documentId);
}
