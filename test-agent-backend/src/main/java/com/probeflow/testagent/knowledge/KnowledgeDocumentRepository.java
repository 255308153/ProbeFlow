package com.probeflow.testagent.knowledge;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, String> {

    Optional<KnowledgeDocument> findBySourceTypeAndSourceRef(DocumentSourceType sourceType, String sourceRef);
}
