package com.probeflow.testagent.memorygraph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "memory_graph_edge")
public class MemoryGraphEdge {

    @Id
    @Column(name = "edge_id", nullable = false, length = 96)
    private String edgeId;

    @Column(name = "source_node_id", nullable = false, length = 96)
    private String sourceNodeId;

    @Column(name = "target_node_id", nullable = false, length = 96)
    private String targetNodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false, length = 64)
    private MemoryGraphRelationType relationType;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getEdgeId() {
        return edgeId;
    }

    public void setEdgeId(String edgeId) {
        this.edgeId = edgeId;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public void setSourceNodeId(String sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public void setTargetNodeId(String targetNodeId) {
        this.targetNodeId = targetNodeId;
    }

    public MemoryGraphRelationType getRelationType() {
        return relationType;
    }

    public void setRelationType(MemoryGraphRelationType relationType) {
        this.relationType = relationType;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
