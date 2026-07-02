package com.probeflow.testagent.changelog;

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
@Table(name = "change_log")
public class ChangeLog {

    @Id
    @Column(name = "change_id", nullable = false, updatable = false, length = 36)
    private String changeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private ChangeEntityType entityType;

    @Column(name = "entity_id", nullable = false, length = 36)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> beforeSnapshot = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> afterSnapshot = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 32)
    private ChangeType changeType;

    @Column(name = "changed_by", nullable = false, length = 128)
    private String changedBy;

    @Column(name = "task_id", length = 36)
    private String taskId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (changeId == null) {
            changeId = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public String getChangeId() {
        return changeId;
    }

    public void setChangeId(String changeId) {
        this.changeId = changeId;
    }

    public ChangeEntityType getEntityType() {
        return entityType;
    }

    public void setEntityType(ChangeEntityType entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public Map<String, Object> getBeforeSnapshot() {
        return beforeSnapshot;
    }

    public void setBeforeSnapshot(Map<String, Object> beforeSnapshot) {
        this.beforeSnapshot = beforeSnapshot;
    }

    public Map<String, Object> getAfterSnapshot() {
        return afterSnapshot;
    }

    public void setAfterSnapshot(Map<String, Object> afterSnapshot) {
        this.afterSnapshot = afterSnapshot;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(ChangeType changeType) {
        this.changeType = changeType;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
