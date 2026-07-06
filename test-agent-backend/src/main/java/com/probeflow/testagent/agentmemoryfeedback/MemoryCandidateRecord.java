package com.probeflow.testagent.agentmemoryfeedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "memory_candidate_record",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_memory_candidate_source_task",
        columnNames = {"source_type", "source_ref", "task_id"}
    )
)
public class MemoryCandidateRecord {

    @Id
    @Column(name = "candidate_id", nullable = false, updatable = false, length = 36)
    private String candidateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 48)
    private AgentMemoryCandidateSourceType sourceType;

    @Column(name = "source_ref", nullable = false, length = 256)
    private String sourceRef;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private MemoryCandidateProcessingStatus status;

    @Column(name = "summary", nullable = false, length = 512)
    private String summary;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "sanitized_evidence", columnDefinition = "text")
    private String sanitizedEvidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false, columnDefinition = "jsonb")
    private List<String> tags = new ArrayList<>();

    @Column(name = "confidence", nullable = false)
    private Float confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "idempotency_key", nullable = false, length = 512)
    private String idempotencyKey;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "refinery_result_summary", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> refineryResultSummary = new LinkedHashMap<>();

    @Column(name = "memory_id", length = 36)
    private String memoryId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audit_summary", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> auditSummary = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        if (candidateId == null) {
            candidateId = UUID.randomUUID().toString();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getCandidateId() {
        return candidateId;
    }

    public void setCandidateId(String candidateId) {
        this.candidateId = candidateId;
    }

    public AgentMemoryCandidateSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(AgentMemoryCandidateSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public MemoryCandidateProcessingStatus getStatus() {
        return status;
    }

    public void setStatus(MemoryCandidateProcessingStatus status) {
        this.status = status;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSanitizedEvidence() {
        return sanitizedEvidence;
    }

    public void setSanitizedEvidence(String sanitizedEvidence) {
        this.sanitizedEvidence = sanitizedEvidence;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public Float getConfidence() {
        return confidence;
    }

    public void setConfidence(Float confidence) {
        this.confidence = confidence;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Map<String, Object> getRefineryResultSummary() {
        return refineryResultSummary;
    }

    public void setRefineryResultSummary(Map<String, Object> refineryResultSummary) {
        this.refineryResultSummary = refineryResultSummary;
    }

    public String getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(String memoryId) {
        this.memoryId = memoryId;
    }

    public Map<String, Object> getAuditSummary() {
        return auditSummary;
    }

    public void setAuditSummary(Map<String, Object> auditSummary) {
        this.auditSummary = auditSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
