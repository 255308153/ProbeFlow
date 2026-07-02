package com.probeflow.testagent.executionrecord;

import jakarta.persistence.Column;
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
@Table(name = "execution_record")
public class ExecutionRecord {

    @Id
    @Column(name = "execution_id", nullable = false, updatable = false, length = 36)
    private String executionId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "case_id", nullable = false, length = 36)
    private String caseId;

    @Column(name = "step_id", length = 36)
    private String stepId;

    @Enumerated(EnumType.STRING)
    @Column(name = "executor_type", nullable = false, length = 16)
    private ExecutorType executorType;

    @Column(name = "environment", length = 64)
    private String environment;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> requestSnapshot = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> responseSnapshot = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assertion_results", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> assertionResults = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_status", nullable = false, length = 32)
    private OverallStatus overallStatus;

    @Column(name = "critical_failed", nullable = false)
    private boolean criticalFailed;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (executionId == null) {
            executionId = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
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

    public String getStepId() {
        return stepId;
    }

    public void setStepId(String stepId) {
        this.stepId = stepId;
    }

    public ExecutorType getExecutorType() {
        return executorType;
    }

    public void setExecutorType(ExecutorType executorType) {
        this.executorType = executorType;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Map<String, Object> getRequestSnapshot() {
        return requestSnapshot;
    }

    public void setRequestSnapshot(Map<String, Object> requestSnapshot) {
        this.requestSnapshot = requestSnapshot;
    }

    public Map<String, Object> getResponseSnapshot() {
        return responseSnapshot;
    }

    public void setResponseSnapshot(Map<String, Object> responseSnapshot) {
        this.responseSnapshot = responseSnapshot;
    }

    public List<Map<String, Object>> getAssertionResults() {
        return assertionResults;
    }

    public void setAssertionResults(List<Map<String, Object>> assertionResults) {
        this.assertionResults = assertionResults;
    }

    public OverallStatus getOverallStatus() {
        return overallStatus;
    }

    public void setOverallStatus(OverallStatus overallStatus) {
        this.overallStatus = overallStatus;
    }

    public boolean isCriticalFailed() {
        return criticalFailed;
    }

    public void setCriticalFailed(boolean criticalFailed) {
        this.criticalFailed = criticalFailed;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
