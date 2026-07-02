package com.probeflow.testagent.taskcaseexecution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "task_case_execution")
public class TaskCaseExecution {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "case_id", nullable = false, length = 36)
    private String caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false, length = 16)
    private ExecutionMode executionMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> snapshotJson = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false, length = 16)
    private TaskCaseExecutionStatus executionStatus;

    @Column(name = "execution_record_id", length = 36)
    private String executionRecordId;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(ExecutionMode executionMode) {
        this.executionMode = executionMode;
    }

    public Map<String, Object> getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(Map<String, Object> snapshotJson) {
        this.snapshotJson = snapshotJson;
    }

    public TaskCaseExecutionStatus getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(TaskCaseExecutionStatus executionStatus) {
        this.executionStatus = executionStatus;
    }

    public String getExecutionRecordId() {
        return executionRecordId;
    }

    public void setExecutionRecordId(String executionRecordId) {
        this.executionRecordId = executionRecordId;
    }
}
