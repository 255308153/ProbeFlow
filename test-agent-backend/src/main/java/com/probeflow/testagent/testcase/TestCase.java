package com.probeflow.testagent.testcase;

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
@Table(name = "test_case")
public class TestCase {

    @Id
    @Column(name = "case_id", nullable = false, updatable = false, length = 36)
    private String caseId;

    @Column(name = "primary_api_spec_id", nullable = false, length = 36)
    private String primaryApiSpecId;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_category", nullable = false, length = 32)
    private CaseCategory caseCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private TestCaseMode mode;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preconditions", nullable = false, columnDefinition = "jsonb")
    private List<String> preconditions = new ArrayList<>();

    @Column(name = "expected_result", columnDefinition = "text")
    private String expectedResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private CasePriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 16)
    private CaseRiskLevel riskLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false, columnDefinition = "jsonb")
    private List<String> tags = new ArrayList<>();

    @Column(name = "scenario_name", length = 255)
    private String scenarioName;

    @Column(name = "module_name", length = 128)
    private String moduleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CaseStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private CaseSource source;

    @Column(name = "manual_edited", nullable = false)
    private boolean manualEdited;

    @Column(name = "locked", nullable = false)
    private boolean locked;

    @Enumerated(EnumType.STRING)
    @Column(name = "detail_type", nullable = false, length = 32)
    private DetailType detailType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> detail = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "steps", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> steps = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "stale_status", nullable = false, length = 16)
    private StaleStatus staleStatus = StaleStatus.FRESH;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "based_on_api_spec_versions", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> basedOnApiSpecVersions = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generated_from_single_case_ids", nullable = false, columnDefinition = "jsonb")
    private List<String> generatedFromSingleCaseIds = new ArrayList<>();

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        if (caseId == null) {
            caseId = UUID.randomUUID().toString();
        }
        if (generatedAt == null) {
            generatedAt = now;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getPrimaryApiSpecId() {
        return primaryApiSpecId;
    }

    public void setPrimaryApiSpecId(String primaryApiSpecId) {
        this.primaryApiSpecId = primaryApiSpecId;
    }

    public CaseCategory getCaseCategory() {
        return caseCategory;
    }

    public void setCaseCategory(CaseCategory caseCategory) {
        this.caseCategory = caseCategory;
    }

    public TestCaseMode getMode() {
        return mode;
    }

    public void setMode(TestCaseMode mode) {
        this.mode = mode;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getPreconditions() {
        return preconditions;
    }

    public void setPreconditions(List<String> preconditions) {
        this.preconditions = preconditions;
    }

    public String getExpectedResult() {
        return expectedResult;
    }

    public void setExpectedResult(String expectedResult) {
        this.expectedResult = expectedResult;
    }

    public CasePriority getPriority() {
        return priority;
    }

    public void setPriority(CasePriority priority) {
        this.priority = priority;
    }

    public CaseRiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(CaseRiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public void setScenarioName(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public void setStatus(CaseStatus status) {
        this.status = status;
    }

    public CaseSource getSource() {
        return source;
    }

    public void setSource(CaseSource source) {
        this.source = source;
    }

    public boolean isManualEdited() {
        return manualEdited;
    }

    public void setManualEdited(boolean manualEdited) {
        this.manualEdited = manualEdited;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public DetailType getDetailType() {
        return detailType;
    }

    public void setDetailType(DetailType detailType) {
        this.detailType = detailType;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public void setDetail(Map<String, Object> detail) {
        this.detail = detail;
    }

    public List<Map<String, Object>> getSteps() {
        return steps;
    }

    public void setSteps(List<Map<String, Object>> steps) {
        this.steps = steps;
    }

    public StaleStatus getStaleStatus() {
        return staleStatus;
    }

    public void setStaleStatus(StaleStatus staleStatus) {
        this.staleStatus = staleStatus;
    }

    public Map<String, Object> getBasedOnApiSpecVersions() {
        return basedOnApiSpecVersions;
    }

    public void setBasedOnApiSpecVersions(Map<String, Object> basedOnApiSpecVersions) {
        this.basedOnApiSpecVersions = basedOnApiSpecVersions;
    }

    public List<String> getGeneratedFromSingleCaseIds() {
        return generatedFromSingleCaseIds;
    }

    public void setGeneratedFromSingleCaseIds(List<String> generatedFromSingleCaseIds) {
        this.generatedFromSingleCaseIds = generatedFromSingleCaseIds;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
