package com.probeflow.testagent.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "memory_usefulness_feedback",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_memory_usefulness_usage_actor_outcome",
        columnNames = {"usage_id", "actor", "outcome"}
    )
)
public class MemoryUsefulnessFeedback {

    @Id
    @Column(name = "feedback_id", nullable = false, updatable = false, length = 36)
    private String feedbackId;

    @Column(name = "usage_id", nullable = false, length = 36)
    private String usageId;

    @Column(name = "memory_id", nullable = false, length = 36)
    private String memoryId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "actor", nullable = false, length = 128)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 24)
    private MemoryUsefulnessOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private MemoryUsefulnessFeedbackStatus status;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "sanitized_summary", nullable = false, columnDefinition = "text")
    private String sanitizedSummary;

    @Column(name = "confidence_delta", nullable = false)
    private Float confidenceDelta = 0.0f;

    @Column(name = "importance_delta", nullable = false)
    private Float importanceDelta = 0.0f;

    @Column(name = "success_contribution_delta", nullable = false)
    private Float successContributionDelta = 0.0f;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 16)
    private MemoryStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 16)
    private MemoryStatus newStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (feedbackId == null) {
            feedbackId = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public String getFeedbackId() {
        return feedbackId;
    }

    public void setFeedbackId(String feedbackId) {
        this.feedbackId = feedbackId;
    }

    public String getUsageId() {
        return usageId;
    }

    public void setUsageId(String usageId) {
        this.usageId = usageId;
    }

    public String getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(String memoryId) {
        this.memoryId = memoryId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public MemoryUsefulnessOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(MemoryUsefulnessOutcome outcome) {
        this.outcome = outcome;
    }

    public MemoryUsefulnessFeedbackStatus getStatus() {
        return status;
    }

    public void setStatus(MemoryUsefulnessFeedbackStatus status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getSanitizedSummary() {
        return sanitizedSummary;
    }

    public void setSanitizedSummary(String sanitizedSummary) {
        this.sanitizedSummary = sanitizedSummary;
    }

    public Float getConfidenceDelta() {
        return confidenceDelta;
    }

    public void setConfidenceDelta(Float confidenceDelta) {
        this.confidenceDelta = confidenceDelta;
    }

    public Float getImportanceDelta() {
        return importanceDelta;
    }

    public void setImportanceDelta(Float importanceDelta) {
        this.importanceDelta = importanceDelta;
    }

    public Float getSuccessContributionDelta() {
        return successContributionDelta;
    }

    public void setSuccessContributionDelta(Float successContributionDelta) {
        this.successContributionDelta = successContributionDelta;
    }

    public MemoryStatus getPreviousStatus() {
        return previousStatus;
    }

    public void setPreviousStatus(MemoryStatus previousStatus) {
        this.previousStatus = previousStatus;
    }

    public MemoryStatus getNewStatus() {
        return newStatus;
    }

    public void setNewStatus(MemoryStatus newStatus) {
        this.newStatus = newStatus;
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
