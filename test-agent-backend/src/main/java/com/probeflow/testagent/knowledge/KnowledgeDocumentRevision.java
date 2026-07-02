package com.probeflow.testagent.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "knowledge_document_revision")
public class KnowledgeDocumentRevision {

    @Id
    @Column(name = "document_revision_id", nullable = false, updatable = false, length = 36)
    private String documentRevisionId;

    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "latest", nullable = false)
    private boolean latest;

    @Enumerated(EnumType.STRING)
    @Column(name = "revision_status", nullable = false, length = 32)
    private RevisionStatus revisionStatus;

    @Column(name = "source_hash", length = 128)
    private String sourceHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (documentRevisionId == null) {
            documentRevisionId = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public String getDocumentRevisionId() {
        return documentRevisionId;
    }

    public void setDocumentRevisionId(String documentRevisionId) {
        this.documentRevisionId = documentRevisionId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public boolean isLatest() {
        return latest;
    }

    public void setLatest(boolean latest) {
        this.latest = latest;
    }

    public RevisionStatus getRevisionStatus() {
        return revisionStatus;
    }

    public void setRevisionStatus(RevisionStatus revisionStatus) {
        this.revisionStatus = revisionStatus;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public void setSourceHash(String sourceHash) {
        this.sourceHash = sourceHash;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
