package com.probeflow.testagent.humanintheloop;

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
@Table(name = "human_decision_record")
public class HumanDecisionRecord {

    @Id
    @Column(name = "decision_id", nullable = false, updatable = false, length = 36)
    private String decisionId;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", nullable = false, length = 48)
    private HumanDecisionType decisionType;

    @Column(name = "actor", nullable = false, length = 128)
    private String actor;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sanitized_payload_summary", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> sanitizedPayloadSummary = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (decisionId == null) {
            decisionId = UUID.randomUUID().toString();
        }
        if (payload == null) {
            payload = new LinkedHashMap<>();
        }
        if (sanitizedPayloadSummary == null) {
            sanitizedPayloadSummary = new LinkedHashMap<>();
        }
        createdAt = Instant.now();
    }

    public String getDecisionId() {
        return decisionId;
    }

    public void setDecisionId(String decisionId) {
        this.decisionId = decisionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public HumanDecisionType getDecisionType() {
        return decisionType;
    }

    public void setDecisionType(HumanDecisionType decisionType) {
        this.decisionType = decisionType;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public Map<String, Object> getSanitizedPayloadSummary() {
        return sanitizedPayloadSummary;
    }

    public void setSanitizedPayloadSummary(Map<String, Object> sanitizedPayloadSummary) {
        this.sanitizedPayloadSummary = sanitizedPayloadSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
