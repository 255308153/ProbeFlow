package com.probeflow.testagent.task;

import com.probeflow.testagent.testcasedraft.PromotionMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
@Table(name = "task")
public class Task {

    @Id
    @Column(name = "task_id", nullable = false, updatable = false, length = 36)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 32)
    private TaskType taskType;

    @Column(name = "task_name", nullable = false, length = 255)
    private String taskName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private TaskSourceType sourceType;

    @Column(name = "source_ref", columnDefinition = "text")
    private String sourceRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_api_spec_ids", nullable = false, columnDefinition = "jsonb")
    private List<String> targetApiSpecIds = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "promotion_mode", nullable = false, length = 16)
    private PromotionMode promotionMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_refinement_status", nullable = false, length = 32)
    private MemoryRefinementStatus memoryRefinementStatus = MemoryRefinementStatus.NOT_REQUIRED;

    @Column(name = "memory_refined_at")
    private Instant memoryRefinedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private TaskPriority priority;

    @Column(name = "creator", length = 128)
    private String creator;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        if (taskId == null) {
            taskId = UUID.randomUUID().toString();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(TaskType taskType) {
        this.taskType = taskType;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public TaskSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(TaskSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public List<String> getTargetApiSpecIds() {
        return targetApiSpecIds;
    }

    public void setTargetApiSpecIds(List<String> targetApiSpecIds) {
        this.targetApiSpecIds = targetApiSpecIds;
    }

    public PromotionMode getPromotionMode() {
        return promotionMode;
    }

    public void setPromotionMode(PromotionMode promotionMode) {
        this.promotionMode = promotionMode;
    }

    public MemoryRefinementStatus getMemoryRefinementStatus() {
        return memoryRefinementStatus;
    }

    public void setMemoryRefinementStatus(MemoryRefinementStatus memoryRefinementStatus) {
        this.memoryRefinementStatus = memoryRefinementStatus;
    }

    public Instant getMemoryRefinedAt() {
        return memoryRefinedAt;
    }

    public void setMemoryRefinedAt(Instant memoryRefinedAt) {
        this.memoryRefinedAt = memoryRefinedAt;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        this.priority = priority;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
