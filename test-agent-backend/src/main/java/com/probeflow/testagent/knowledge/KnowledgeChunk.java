package com.probeflow.testagent.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunk {

    @Id
    @Column(name = "chunk_id", nullable = false, updatable = false, length = 36)
    private String chunkId;

    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;

    @Column(name = "document_revision_id", nullable = false, length = 36)
    private String documentRevisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_status", nullable = false, length = 16)
    private ChunkStatus chunkStatus;

    @Column(name = "chunk_title", length = 255)
    private String chunkTitle;

    @Column(name = "chunk_content", nullable = false, columnDefinition = "text")
    private String chunkContent;

    @Column(name = "chunk_order", nullable = false)
    private Integer chunkOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false, columnDefinition = "jsonb")
    private List<String> tags = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applicable_stages", nullable = false, columnDefinition = "jsonb")
    private List<String> applicableStages = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "token_count", nullable = false)
    private Integer tokenCount = 0;

    @Convert(converter = EmbeddingVectorConverter.class)
    @Column(name = "embedding", nullable = false, columnDefinition = "vector(1024)")
    private float[] embedding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (chunkId == null) {
            chunkId = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getDocumentRevisionId() {
        return documentRevisionId;
    }

    public void setDocumentRevisionId(String documentRevisionId) {
        this.documentRevisionId = documentRevisionId;
    }

    public ChunkStatus getChunkStatus() {
        return chunkStatus;
    }

    public void setChunkStatus(ChunkStatus chunkStatus) {
        this.chunkStatus = chunkStatus;
    }

    public String getChunkTitle() {
        return chunkTitle;
    }

    public void setChunkTitle(String chunkTitle) {
        this.chunkTitle = chunkTitle;
    }

    public String getChunkContent() {
        return chunkContent;
    }

    public void setChunkContent(String chunkContent) {
        this.chunkContent = chunkContent;
    }

    public Integer getChunkOrder() {
        return chunkOrder;
    }

    public void setChunkOrder(Integer chunkOrder) {
        this.chunkOrder = chunkOrder;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public List<String> getApplicableStages() {
        return applicableStages;
    }

    public void setApplicableStages(List<String> applicableStages) {
        this.applicableStages = applicableStages;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
