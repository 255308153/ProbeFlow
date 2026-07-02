package com.probeflow.testagent.testcasedraft;

import com.probeflow.testagent.testcase.CaseSource;
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
@Table(name = "test_case_draft")
public class TestCaseDraft {

    @Id
    @Column(name = "draft_id", nullable = false, updatable = false, length = 36)
    private String draftId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private CaseSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 32)
    private CaseSource stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DraftStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "promotion_mode", nullable = false, length = 16)
    private PromotionMode promotionMode;

    @Column(name = "target_api_spec_id", nullable = false, length = 36)
    private String targetApiSpecId;

    @Column(name = "dedup_key", nullable = false, length = 512)
    private String dedupKey;

    @Column(name = "expected_status_code")
    private Integer expectedStatusCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_content", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> draftContent = new LinkedHashMap<>();

    @Column(name = "promoted_case_id", length = 36)
    private String promotedCaseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (draftId == null) {
            draftId = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public String getDraftId() {
        return draftId;
    }

    public void setDraftId(String draftId) {
        this.draftId = draftId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public CaseSource getSource() {
        return source;
    }

    public void setSource(CaseSource source) {
        this.source = source;
    }

    public CaseSource getStage() {
        return stage;
    }

    public void setStage(CaseSource stage) {
        this.stage = stage;
    }

    public DraftStatus getStatus() {
        return status;
    }

    public void setStatus(DraftStatus status) {
        this.status = status;
    }

    public PromotionMode getPromotionMode() {
        return promotionMode;
    }

    public void setPromotionMode(PromotionMode promotionMode) {
        this.promotionMode = promotionMode;
    }

    public String getTargetApiSpecId() {
        return targetApiSpecId;
    }

    public void setTargetApiSpecId(String targetApiSpecId) {
        this.targetApiSpecId = targetApiSpecId;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public Integer getExpectedStatusCode() {
        return expectedStatusCode;
    }

    public void setExpectedStatusCode(Integer expectedStatusCode) {
        this.expectedStatusCode = expectedStatusCode;
    }

    public Map<String, Object> getDraftContent() {
        return draftContent;
    }

    public void setDraftContent(Map<String, Object> draftContent) {
        this.draftContent = draftContent;
    }

    public String getPromotedCaseId() {
        return promotedCaseId;
    }

    public void setPromotedCaseId(String promotedCaseId) {
        this.promotedCaseId = promotedCaseId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
