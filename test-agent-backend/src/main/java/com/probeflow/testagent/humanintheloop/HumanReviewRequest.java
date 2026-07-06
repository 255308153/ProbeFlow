package com.probeflow.testagent.humanintheloop;

import com.probeflow.testagent.agentpolicy.ToolRiskLevel;
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
@Table(name = "human_review_request")
public class HumanReviewRequest {

    @Id
    @Column(name = "request_id", nullable = false, updatable = false, length = 36)
    private String requestId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "source_step_id", length = 36)
    private String sourceStepId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 48)
    private HumanRequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private HumanRequestStatus status = HumanRequestStatus.PENDING;

    @Column(name = "waiting_reason", nullable = false, columnDefinition = "text")
    private String waitingReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_input_schema", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> requiredInputSchema = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 16)
    private ToolRiskLevel riskLevel = ToolRiskLevel.LOW;

    @Column(name = "source_trigger", nullable = false, length = 64)
    private String sourceTrigger;

    @Column(name = "planner_decision_id", length = 128)
    private String plannerDecisionId;

    @Column(name = "policy_reason", length = 128)
    private String policyReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = HumanRequestStatus.PENDING;
        }
        if (requiredInputSchema == null) {
            requiredInputSchema = new ArrayList<>();
        }
        if (riskLevel == null) {
            riskLevel = ToolRiskLevel.LOW;
        }
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void markAnswered() {
        status = HumanRequestStatus.ANSWERED;
        answeredAt = Instant.now();
    }

    public void markConsumed() {
        status = HumanRequestStatus.CONSUMED;
        consumedAt = Instant.now();
    }

    public void markRejected() {
        status = HumanRequestStatus.REJECTED;
        answeredAt = Instant.now();
    }

    public void cancel() {
        status = HumanRequestStatus.CANCELLED;
    }

    public void expire() {
        status = HumanRequestStatus.EXPIRED;
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

    public String getSourceStepId() {
        return sourceStepId;
    }

    public void setSourceStepId(String sourceStepId) {
        this.sourceStepId = sourceStepId;
    }

    public HumanRequestType getRequestType() {
        return requestType;
    }

    public void setRequestType(HumanRequestType requestType) {
        this.requestType = requestType;
    }

    public HumanRequestStatus getStatus() {
        return status;
    }

    public void setStatus(HumanRequestStatus status) {
        this.status = status;
    }

    public String getWaitingReason() {
        return waitingReason;
    }

    public void setWaitingReason(String waitingReason) {
        this.waitingReason = waitingReason;
    }

    public List<Map<String, Object>> getRequiredInputSchema() {
        return requiredInputSchema;
    }

    public void setRequiredInputSchema(List<Map<String, Object>> requiredInputSchema) {
        this.requiredInputSchema = requiredInputSchema;
    }

    public ToolRiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(ToolRiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getSourceTrigger() {
        return sourceTrigger;
    }

    public void setSourceTrigger(String sourceTrigger) {
        this.sourceTrigger = sourceTrigger;
    }

    public String getPlannerDecisionId() {
        return plannerDecisionId;
    }

    public void setPlannerDecisionId(String plannerDecisionId) {
        this.plannerDecisionId = plannerDecisionId;
    }

    public String getPolicyReason() {
        return policyReason;
    }

    public void setPolicyReason(String policyReason) {
        this.policyReason = policyReason;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
