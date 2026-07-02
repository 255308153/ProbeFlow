package com.probeflow.testagent.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

@RedisHash("session_memory_item")
public class SessionMemoryItem {

    @Id
    private String memoryId;

    private String sessionId;

    private final MemoryType memoryType = MemoryType.SESSION;

    private MemoryScopeType scopeType;

    private String summary;

    private String content;

    private List<String> tags = new ArrayList<>();

    private MemorySourceType sourceType;

    private String sourceRef;

    private Float confidence;

    private MemoryStatus status = MemoryStatus.ACTIVE;

    private Map<String, Object> metadata = new LinkedHashMap<>();

    private Instant createdAt;

    private Instant expiresAt;

    @TimeToLive
    private Long ttlSeconds = 86_400L;

    public String getMemoryId() {
        if (memoryId == null) {
            memoryId = UUID.randomUUID().toString();
        }
        return memoryId;
    }

    public void setMemoryId(String memoryId) {
        this.memoryId = memoryId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public MemoryType getMemoryType() {
        return memoryType;
    }

    public MemoryScopeType getScopeType() {
        return scopeType;
    }

    public void setScopeType(MemoryScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public MemorySourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(MemorySourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public Float getConfidence() {
        return confidence;
    }

    public void setConfidence(Float confidence) {
        this.confidence = confidence;
    }

    public MemoryStatus getStatus() {
        return status;
    }

    public void setStatus(MemoryStatus status) {
        this.status = status;
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

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(Long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
