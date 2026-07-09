package com.probeflow.testagent.memorygraph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "memory_graph_node")
public class MemoryGraphNode {

    @Id
    @Column(name = "node_id", nullable = false, length = 96)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 48)
    private MemoryGraphEntityType entityType;

    @Column(name = "normalized_value", nullable = false, length = 512)
    private String normalizedValue;

    @Column(name = "display_value", nullable = false, length = 512)
    private String displayValue;

    @Column(name = "scope", length = 512)
    private String scope;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_memory_ids", nullable = false, columnDefinition = "jsonb")
    private List<String> sourceMemoryIds = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_refs", nullable = false, columnDefinition = "jsonb")
    private List<String> sourceRefs = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fact_fingerprints", nullable = false, columnDefinition = "jsonb")
    private List<String> factFingerprints = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_summaries", nullable = false, columnDefinition = "jsonb")
    private List<String> evidenceSummaries = new ArrayList<>();

    @Column(name = "occurrence_count", nullable = false)
    private Integer occurrenceCount = 0;

    @Column(name = "confidence", nullable = false)
    private Double confidence = 0.0d;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public MemoryGraphEntityType getEntityType() {
        return entityType;
    }

    public void setEntityType(MemoryGraphEntityType entityType) {
        this.entityType = entityType;
    }

    public String getNormalizedValue() {
        return normalizedValue;
    }

    public void setNormalizedValue(String normalizedValue) {
        this.normalizedValue = normalizedValue;
    }

    public String getDisplayValue() {
        return displayValue;
    }

    public void setDisplayValue(String displayValue) {
        this.displayValue = displayValue;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public List<String> getSourceMemoryIds() {
        return sourceMemoryIds;
    }

    public void setSourceMemoryIds(List<String> sourceMemoryIds) {
        this.sourceMemoryIds = sourceMemoryIds;
    }

    public List<String> getSourceRefs() {
        return sourceRefs;
    }

    public void setSourceRefs(List<String> sourceRefs) {
        this.sourceRefs = sourceRefs;
    }

    public List<String> getFactFingerprints() {
        return factFingerprints;
    }

    public void setFactFingerprints(List<String> factFingerprints) {
        this.factFingerprints = factFingerprints;
    }

    public List<String> getEvidenceSummaries() {
        return evidenceSummaries;
    }

    public void setEvidenceSummaries(List<String> evidenceSummaries) {
        this.evidenceSummaries = evidenceSummaries;
    }

    public Integer getOccurrenceCount() {
        return occurrenceCount;
    }

    public void setOccurrenceCount(Integer occurrenceCount) {
        this.occurrenceCount = occurrenceCount;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
