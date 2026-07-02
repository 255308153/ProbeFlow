package com.probeflow.testagent.sourcematerial;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_material")
public class SourceMaterial {

    @Id
    @Column(name = "material_id", nullable = false, updatable = false, length = 36)
    private String materialId;

    @Column(name = "task_id", length = 36)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "material_type", nullable = false, length = 32)
    private MaterialType materialType;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @Column(name = "original_ref", columnDefinition = "text")
    private String originalRef;

    @Column(name = "storage_path", columnDefinition = "text")
    private String storagePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "ingest_status", nullable = false, length = 16)
    private IngestStatus ingestStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        if (materialId == null) {
            materialId = UUID.randomUUID().toString();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getMaterialId() {
        return materialId;
    }

    public void setMaterialId(String materialId) {
        this.materialId = materialId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public MaterialType getMaterialType() {
        return materialType;
    }

    public void setMaterialType(MaterialType materialType) {
        this.materialType = materialType;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getOriginalRef() {
        return originalRef;
    }

    public void setOriginalRef(String originalRef) {
        this.originalRef = originalRef;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public IngestStatus getIngestStatus() {
        return ingestStatus;
    }

    public void setIngestStatus(IngestStatus ingestStatus) {
        this.ingestStatus = ingestStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
