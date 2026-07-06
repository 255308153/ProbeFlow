package com.probeflow.testagent.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "report")
public class Report {

    @Id
    @Column(name = "report_id", nullable = false, updatable = false, length = 36)
    private String reportId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "case_count", nullable = false)
    private Integer caseCount = 0;

    @Column(name = "pass_count", nullable = false)
    private Integer passCount = 0;

    @Column(name = "fail_count", nullable = false)
    private Integer failCount = 0;

    @Column(name = "warning_count", nullable = false)
    private Integer warningCount = 0;

    @Column(name = "risk_summary", columnDefinition = "text")
    private String riskSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> findings = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggestions", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> suggestions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = Map.of();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (reportId == null) {
            reportId = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Integer getCaseCount() {
        return caseCount;
    }

    public void setCaseCount(Integer caseCount) {
        this.caseCount = caseCount;
    }

    public Integer getPassCount() {
        return passCount;
    }

    public void setPassCount(Integer passCount) {
        this.passCount = passCount;
    }

    public Integer getFailCount() {
        return failCount;
    }

    public void setFailCount(Integer failCount) {
        this.failCount = failCount;
    }

    public Integer getWarningCount() {
        return warningCount;
    }

    public void setWarningCount(Integer warningCount) {
        this.warningCount = warningCount;
    }

    public String getRiskSummary() {
        return riskSummary;
    }

    public void setRiskSummary(String riskSummary) {
        this.riskSummary = riskSummary;
    }

    public List<Map<String, Object>> getFindings() {
        return findings;
    }

    public void setFindings(List<Map<String, Object>> findings) {
        this.findings = findings;
    }

    public List<Map<String, Object>> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<Map<String, Object>> suggestions) {
        this.suggestions = suggestions;
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
