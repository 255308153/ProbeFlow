package com.probeflow.testagent.apispec;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "api_spec")
public class ApiSpec {

    @Id
    @Column(name = "api_spec_id", nullable = false, updatable = false, length = 36)
    private String apiSpecId;

    @Column(name = "system_name", nullable = false, length = 128)
    private String systemName;

    @Column(name = "module_name", nullable = false, length = 128)
    private String moduleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "http_method", nullable = false, length = 16)
    private HttpMethod httpMethod;

    @Column(name = "path", nullable = false, length = 1024)
    private String path;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "operation_id", length = 255)
    private String operationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> parameters = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "constraints_doc", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> constraints = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "auth", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> auth = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private ApiSpecSourceType sourceType;

    @Column(name = "source_ref", columnDefinition = "text")
    private String sourceRef;

    @Column(name = "source_material_id", length = 36)
    private String sourceMaterialId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_location", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> sourceLocation = new LinkedHashMap<>();

    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Column(name = "route_ready", nullable = false)
    private boolean routeReady;

    @Column(name = "basic_param_ready", nullable = false)
    private boolean basicParamReady;

    @Column(name = "dto_expanded", nullable = false)
    private boolean dtoExpanded;

    @Column(name = "validation_ready", nullable = false)
    private boolean validationReady;

    @Column(name = "auth_ready", nullable = false)
    private boolean authReady;

    @Column(name = "knowledge_context_ready", nullable = false)
    private boolean knowledgeContextReady;

    @Column(name = "present_in_latest_analysis", nullable = false)
    private boolean presentInLatestAnalysis = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        if (apiSpecId == null) {
            apiSpecId = UUID.randomUUID().toString();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getApiSpecId() {
        return apiSpecId;
    }

    public void setApiSpecId(String apiSpecId) {
        this.apiSpecId = apiSpecId;
    }

    public String getSystemName() {
        return systemName;
    }

    public void setSystemName(String systemName) {
        this.systemName = systemName;
    }

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    public HttpMethod getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(HttpMethod httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public Map<String, Object> getConstraints() {
        return constraints;
    }

    public void setConstraints(Map<String, Object> constraints) {
        this.constraints = constraints;
    }

    public Map<String, Object> getAuth() {
        return auth;
    }

    public void setAuth(Map<String, Object> auth) {
        this.auth = auth;
    }

    public ApiSpecSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(ApiSpecSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getSourceMaterialId() {
        return sourceMaterialId;
    }

    public void setSourceMaterialId(String sourceMaterialId) {
        this.sourceMaterialId = sourceMaterialId;
    }

    public Map<String, Object> getSourceLocation() {
        return sourceLocation;
    }

    public void setSourceLocation(Map<String, Object> sourceLocation) {
        this.sourceLocation = sourceLocation;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public boolean isRouteReady() {
        return routeReady;
    }

    public void setRouteReady(boolean routeReady) {
        this.routeReady = routeReady;
    }

    public boolean isBasicParamReady() {
        return basicParamReady;
    }

    public void setBasicParamReady(boolean basicParamReady) {
        this.basicParamReady = basicParamReady;
    }

    public boolean isDtoExpanded() {
        return dtoExpanded;
    }

    public void setDtoExpanded(boolean dtoExpanded) {
        this.dtoExpanded = dtoExpanded;
    }

    public boolean isValidationReady() {
        return validationReady;
    }

    public void setValidationReady(boolean validationReady) {
        this.validationReady = validationReady;
    }

    public boolean isAuthReady() {
        return authReady;
    }

    public void setAuthReady(boolean authReady) {
        this.authReady = authReady;
    }

    public boolean isKnowledgeContextReady() {
        return knowledgeContextReady;
    }

    public void setKnowledgeContextReady(boolean knowledgeContextReady) {
        this.knowledgeContextReady = knowledgeContextReady;
    }

    public boolean isPresentInLatestAnalysis() {
        return presentInLatestAnalysis;
    }

    public void setPresentInLatestAnalysis(boolean presentInLatestAnalysis) {
        this.presentInLatestAnalysis = presentInLatestAnalysis;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
