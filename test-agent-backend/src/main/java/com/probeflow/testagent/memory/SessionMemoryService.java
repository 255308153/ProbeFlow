package com.probeflow.testagent.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SessionMemoryService {

    private static final long DEFAULT_TTL_SECONDS = 86_400L;

    private final SessionMemoryStore sessionMemories;

    public SessionMemoryService(SessionMemoryStore sessionMemories) {
        this.sessionMemories = sessionMemories;
    }

    @Transactional
    public SessionMemoryWriteResult writeSessionMemory(SessionMemoryWriteRequest request) {
        validate(request);

        var normalized = normalize(request);
        var now = Instant.now();

        var memory = new SessionMemoryItem();
        memory.setSessionId(normalized.sessionId());
        memory.setScopeType(normalized.scopeType());
        memory.setSummary(normalized.summary());
        memory.setContent(normalized.content());
        memory.setTags(normalized.tags());
        memory.setSourceType(normalized.sourceType());
        memory.setSourceRef(normalized.sourceRef());
        memory.setConfidence(normalized.confidence());
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setMetadata(normalized.metadata());
        memory.setCreatedAt(now);
        memory.setTtlSeconds(normalized.ttlSeconds());
        memory.setExpiresAt(now.plusSeconds(normalized.ttlSeconds()));

        var saved = sessionMemories.save(memory);
        return new SessionMemoryWriteResult(saved.getMemoryId());
    }

    @Transactional(readOnly = true)
    public List<SessionMemoryView> readActiveSessionMemories(String sessionId) {
        requireNonBlank(sessionId, "sessionId must not be blank");
        var normalizedSessionId = sessionId.trim();
        var now = Instant.now();

        return sessionMemories.findBySessionId(normalizedSessionId).stream()
            .filter(item -> item.getStatus() == MemoryStatus.ACTIVE)
            .filter(item -> !isExpired(item, now))
            .sorted(Comparator.comparing(SessionMemoryItem::getCreatedAt).thenComparing(SessionMemoryItem::getMemoryId))
            .map(this::toView)
            .toList();
    }

    @Transactional
    public void deactivateSessionMemory(String memoryId) {
        requireNonBlank(memoryId, "memoryId must not be blank");
        var memory = sessionMemories.findById(memoryId.trim())
            .orElseThrow(() -> new IllegalArgumentException("SessionMemoryItem not found: " + memoryId));
        memory.setStatus(MemoryStatus.INACTIVE);
        sessionMemories.save(memory);
    }

    private SessionMemoryView toView(SessionMemoryItem memory) {
        return new SessionMemoryView(
            memory.getMemoryId(),
            memory.getSessionId(),
            memory.getScopeType(),
            memory.getSummary(),
            memory.getContent(),
            List.copyOf(memory.getTags()),
            memory.getSourceType(),
            memory.getSourceRef(),
            memory.getConfidence(),
            new LinkedHashMap<>(memory.getMetadata()),
            memory.getCreatedAt(),
            memory.getExpiresAt(),
            memory.getTtlSeconds()
        );
    }

    private boolean isExpired(SessionMemoryItem memory, Instant now) {
        return memory.getExpiresAt() != null && !memory.getExpiresAt().isAfter(now);
    }

    private void validate(SessionMemoryWriteRequest request) {
        requireNonBlank(request.sessionId(), "sessionId must not be blank");
        requireNonNull(request.scopeType(), "scopeType must not be null");
        requireNonBlank(request.summary(), "summary must not be blank");
        requireNonBlank(request.content(), "content must not be blank");
        requireNonNull(request.sourceType(), "sourceType must not be null");
        requireNonNull(request.confidence(), "confidence must not be null");
        if (request.confidence() < 0.0f || request.confidence() > 1.0f) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
        if (request.ttlSeconds() != null && request.ttlSeconds() <= 0L) {
            throw new IllegalArgumentException("ttlSeconds must be positive");
        }
    }

    private SessionMemoryWriteRequest normalize(SessionMemoryWriteRequest request) {
        return new SessionMemoryWriteRequest(
            request.sessionId().trim(),
            request.scopeType(),
            request.summary().trim(),
            request.content().trim(),
            normalizeTags(request.tags()),
            request.sourceType(),
            normalizeNullable(request.sourceRef()),
            request.confidence(),
            normalizeMetadata(request.metadata()),
            request.ttlSeconds() == null ? DEFAULT_TTL_SECONDS : request.ttlSeconds()
        );
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
            .filter(StringUtils::hasText)
            .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
            .distinct()
            .sorted()
            .toList();
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        var sorted = new TreeMap<String, Object>();
        metadata.forEach((key, value) -> {
            if (StringUtils.hasText(key)) {
                sorted.put(key.trim(), normalizeMetadataValue(value));
            }
        });
        return new LinkedHashMap<>(sorted);
    }

    private Object normalizeMetadataValue(Object value) {
        if (value instanceof String stringValue) {
            return stringValue.trim();
        }
        if (value instanceof Map<?, ?> nestedMap) {
            var normalized = new TreeMap<String, Object>();
            nestedMap.forEach((key, nestedValue) -> {
                if (key != null && StringUtils.hasText(key.toString())) {
                    normalized.put(key.toString().trim(), normalizeMetadataValue(nestedValue));
                }
            });
            return new LinkedHashMap<>(normalized);
        }
        if (value instanceof List<?> listValue) {
            var normalized = new ArrayList<Object>();
            for (var item : listValue) {
                normalized.add(normalizeMetadataValue(item));
            }
            return List.copyOf(normalized);
        }
        return value;
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void requireNonBlank(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
    }
}
