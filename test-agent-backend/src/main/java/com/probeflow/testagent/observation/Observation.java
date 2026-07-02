package com.probeflow.testagent.observation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "observation")
public class Observation {

    @Id
    @Column(name = "observation_id", nullable = false, updatable = false, length = 36)
    private String observationId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "execution_id", nullable = false, length = 36)
    private String executionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "observation_type", nullable = false, length = 48)
    private ObservationType observationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_level", nullable = false, length = 16)
    private AnalysisLevel analysisLevel;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 16)
    private ObservationRiskLevel riskLevel;

    @Column(name = "next_suggestion", columnDefinition = "text")
    private String nextSuggestion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private ObservationSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (observationId == null) {
            observationId = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public String getObservationId() {
        return observationId;
    }

    public void setObservationId(String observationId) {
        this.observationId = observationId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public ObservationType getObservationType() {
        return observationType;
    }

    public void setObservationType(ObservationType observationType) {
        this.observationType = observationType;
    }

    public AnalysisLevel getAnalysisLevel() {
        return analysisLevel;
    }

    public void setAnalysisLevel(AnalysisLevel analysisLevel) {
        this.analysisLevel = analysisLevel;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public ObservationRiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(ObservationRiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getNextSuggestion() {
        return nextSuggestion;
    }

    public void setNextSuggestion(String nextSuggestion) {
        this.nextSuggestion = nextSuggestion;
    }

    public ObservationSource getSource() {
        return source;
    }

    public void setSource(ObservationSource source) {
        this.source = source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
