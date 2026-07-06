package com.probeflow.testagent.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "llm_call_log")
public class LlmCallLog {

    @Id
    @Column(name = "llm_call_id", nullable = false, updatable = false, length = 36)
    private String llmCallId;

    @Column(name = "task_id", length = 36)
    private String taskId;

    @Column(name = "plan_step_id", length = 36)
    private String planStepId;

    @Column(name = "purpose", nullable = false, length = 64)
    private String purpose;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "template_id", length = 128)
    private String templateId;

    @Column(name = "template_version", length = 32)
    private String templateVersion;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LlmCallStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", length = 48)
    private LlmErrorType errorType;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens = 0;

    @Column(name = "total_tokens", nullable = false)
    private Integer totalTokens = 0;

    @Column(name = "prompt_summary", columnDefinition = "text")
    private String promptSummary;

    @Column(name = "response_summary", columnDefinition = "text")
    private String responseSummary;

    @Column(name = "provider_trace_id", length = 128)
    private String providerTraceId;

    @Column(name = "fake_provider", nullable = false)
    private boolean fakeProvider;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = Map.of();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (llmCallId == null) {
            llmCallId = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (errorType == null) {
            errorType = LlmErrorType.NONE;
        }
        if (promptTokens == null) {
            promptTokens = 0;
        }
        if (completionTokens == null) {
            completionTokens = 0;
        }
        if (totalTokens == null) {
            totalTokens = promptTokens + completionTokens;
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    public String getLlmCallId() {
        return llmCallId;
    }

    public void setLlmCallId(String llmCallId) {
        this.llmCallId = llmCallId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getPlanStepId() {
        return planStepId;
    }

    public void setPlanStepId(String planStepId) {
        this.planStepId = planStepId;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(String templateVersion) {
        this.templateVersion = templateVersion;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public LlmCallStatus getStatus() {
        return status;
    }

    public void setStatus(LlmCallStatus status) {
        this.status = status;
    }

    public LlmErrorType getErrorType() {
        return errorType;
    }

    public void setErrorType(LlmErrorType errorType) {
        this.errorType = errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public String getPromptSummary() {
        return promptSummary;
    }

    public void setPromptSummary(String promptSummary) {
        this.promptSummary = promptSummary;
    }

    public String getResponseSummary() {
        return responseSummary;
    }

    public void setResponseSummary(String responseSummary) {
        this.responseSummary = responseSummary;
    }

    public String getProviderTraceId() {
        return providerTraceId;
    }

    public void setProviderTraceId(String providerTraceId) {
        this.providerTraceId = providerTraceId;
    }

    public boolean isFakeProvider() {
        return fakeProvider;
    }

    public void setFakeProvider(boolean fakeProvider) {
        this.fakeProvider = fakeProvider;
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
