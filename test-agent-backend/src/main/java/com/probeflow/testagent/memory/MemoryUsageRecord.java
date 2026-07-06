package com.probeflow.testagent.memory;

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
@Table(name = "memory_usage_record")
public class MemoryUsageRecord {

    @Id
    @Column(name = "usage_id", nullable = false, updatable = false, length = 36)
    private String usageId;

    @Column(name = "memory_id", nullable = false, length = 36)
    private String memoryId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Column(name = "stage_profile", nullable = false, length = 64)
    private String stageProfile;

    @Enumerated(EnumType.STRING)
    @Column(name = "consumer", nullable = false, length = 48)
    private MemoryUsageConsumer consumer;

    @Column(name = "source_ref", nullable = false, columnDefinition = "text")
    private String sourceRef;

    @Column(name = "citation_type", nullable = false, length = 48)
    private String citationType;

    @Column(name = "citation_source_id", nullable = false, length = 128)
    private String citationSourceId;

    @Column(name = "citation_source_ref", columnDefinition = "text")
    private String citationSourceRef;

    @Column(name = "score", nullable = false)
    private Double score;

    @Column(name = "confidence")
    private Float confidence;

    @Column(name = "low_confidence", nullable = false)
    private Boolean lowConfidence = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "match_reasons", nullable = false, columnDefinition = "jsonb")
    private List<String> matchReasons = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (usageId == null) {
            usageId = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
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

    public String getStageProfile() {
        return stageProfile;
    }

    public void setStageProfile(String stageProfile) {
        this.stageProfile = stageProfile;
    }

    public MemoryUsageConsumer getConsumer() {
        return consumer;
    }

    public void setConsumer(MemoryUsageConsumer consumer) {
        this.consumer = consumer;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getCitationType() {
        return citationType;
    }

    public void setCitationType(String citationType) {
        this.citationType = citationType;
    }

    public String getCitationSourceId() {
        return citationSourceId;
    }

    public void setCitationSourceId(String citationSourceId) {
        this.citationSourceId = citationSourceId;
    }

    public String getCitationSourceRef() {
        return citationSourceRef;
    }

    public void setCitationSourceRef(String citationSourceRef) {
        this.citationSourceRef = citationSourceRef;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public Float getConfidence() {
        return confidence;
    }

    public void setConfidence(Float confidence) {
        this.confidence = confidence;
    }

    public Boolean getLowConfidence() {
        return lowConfidence;
    }

    public void setLowConfidence(Boolean lowConfidence) {
        this.lowConfidence = lowConfidence;
    }

    public List<String> getMatchReasons() {
        return matchReasons;
    }

    public void setMatchReasons(List<String> matchReasons) {
        this.matchReasons = matchReasons;
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
