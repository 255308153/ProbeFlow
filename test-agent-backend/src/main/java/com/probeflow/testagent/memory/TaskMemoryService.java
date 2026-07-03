package com.probeflow.testagent.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskMemoryService {

    private final TaskMemoryItemRepository taskMemories;

    public TaskMemoryService(TaskMemoryItemRepository taskMemories) {
        this.taskMemories = taskMemories;
    }

    @Transactional
    public TaskMemoryWriteResult writeTaskMemory(TaskMemoryWriteRequest request) {
        validate(request);

        var normalized = normalize(request);
        var now = Instant.now();
        var existing = taskMemories.findAllByTaskIdAndStatusOrderByCreatedAtAscMemoryIdAsc(
                normalized.taskId(),
                MemoryStatus.ACTIVE
            ).stream()
            .filter(memory -> !isExpired(memory, now))
            .filter(memory -> equivalent(memory, normalized))
            .findFirst();

        if (existing.isPresent()) {
            return new TaskMemoryWriteResult(existing.get().getMemoryId(), false, true);
        }

        var memory = new TaskMemoryItem();
        memory.setTaskId(normalized.taskId());
        memory.setScopeType(normalized.scopeType());
        memory.setSummary(normalized.summary());
        memory.setContent(normalized.content());
        memory.setTags(normalized.tags());
        memory.setSourceType(normalized.sourceType());
        memory.setSourceRef(normalized.sourceRef());
        memory.setConfidence(normalized.confidence());
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setLifecycleStage(normalized.lifecycleStage());
        memory.setMetadata(normalized.metadata());
        memory.setExpiresAt(normalized.expiresAt());

        var saved = taskMemories.save(memory);
        return new TaskMemoryWriteResult(saved.getMemoryId(), true, false);
    }

    @Transactional(readOnly = true)
    public List<TaskMemoryView> readActiveTaskMemories(String taskId) {
        return readActiveTaskMemories(taskId, null);
    }

    @Transactional(readOnly = true)
    public List<TaskMemoryView> readActiveTaskMemories(String taskId, String lifecycleStage) {
        requireNonBlank(taskId, "taskId must not be blank");
        var normalizedTaskId = taskId.trim();
        var normalizedLifecycleStage = normalizeNullableLowercase(lifecycleStage);
        var now = Instant.now();

        return taskMemories.findAllByTaskIdAndStatusOrderByCreatedAtAscMemoryIdAsc(normalizedTaskId, MemoryStatus.ACTIVE)
            .stream()
            .filter(memory -> !isExpired(memory, now))
            .filter(memory -> normalizedLifecycleStage == null || normalizedLifecycleStage.equals(memory.getLifecycleStage()))
            .sorted(Comparator.comparing(TaskMemoryItem::getCreatedAt).thenComparing(TaskMemoryItem::getMemoryId))
            .map(this::toView)
            .toList();
    }

    @Transactional
    public void deactivateTaskMemory(String memoryId) {
        updateStatus(memoryId, MemoryStatus.INACTIVE);
    }

    @Transactional
    public void archiveTaskMemory(String memoryId) {
        updateStatus(memoryId, MemoryStatus.ARCHIVED);
    }

    private void updateStatus(String memoryId, MemoryStatus status) {
        requireNonBlank(memoryId, "memoryId must not be blank");
        var memory = taskMemories.findById(memoryId.trim())
            .orElseThrow(() -> new IllegalArgumentException("TaskMemoryItem not found: " + memoryId));
        memory.setStatus(status);
        taskMemories.save(memory);
    }

    private TaskMemoryView toView(TaskMemoryItem memory) {
        return new TaskMemoryView(
            memory.getMemoryId(),
            memory.getTaskId(),
            memory.getScopeType(),
            memory.getSummary(),
            memory.getContent(),
            List.copyOf(memory.getTags()),
            memory.getSourceType(),
            memory.getSourceRef(),
            memory.getConfidence(),
            memory.getLifecycleStage(),
            new LinkedHashMap<>(memory.getMetadata()),
            memory.getCreatedAt(),
            memory.getUpdatedAt(),
            memory.getExpiresAt()
        );
    }

    private boolean equivalent(TaskMemoryItem memory, TaskMemoryWriteRequest request) {
        return memory.getScopeType() == request.scopeType()
            && Objects.equals(memory.getSummary(), request.summary())
            && Objects.equals(memory.getContent(), request.content())
            && Objects.equals(memory.getTags(), request.tags())
            && memory.getSourceType() == request.sourceType()
            && Objects.equals(memory.getSourceRef(), request.sourceRef())
            && Objects.equals(memory.getConfidence(), request.confidence())
            && Objects.equals(memory.getLifecycleStage(), request.lifecycleStage())
            && Objects.equals(memory.getMetadata(), request.metadata())
            && Objects.equals(memory.getExpiresAt(), request.expiresAt());
    }

    private boolean isExpired(TaskMemoryItem memory, Instant now) {
        return memory.getExpiresAt() != null && !memory.getExpiresAt().isAfter(now);
    }

    private void validate(TaskMemoryWriteRequest request) {
        requireNonBlank(request.taskId(), "taskId must not be blank");
        requireNonNull(request.scopeType(), "scopeType must not be null");
        requireNonBlank(request.summary(), "summary must not be blank");
        requireNonBlank(request.content(), "content must not be blank");
        requireNonNull(request.sourceType(), "sourceType must not be null");
        requireNonBlank(request.lifecycleStage(), "lifecycleStage must not be blank");
        requireNonNull(request.confidence(), "confidence must not be null");
        if (request.confidence() < 0.0f || request.confidence() > 1.0f) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
    }

    private TaskMemoryWriteRequest normalize(TaskMemoryWriteRequest request) {
        return new TaskMemoryWriteRequest(
            request.taskId().trim(),
            request.scopeType(),
            request.summary().trim(),
            request.content().trim(),
            normalizeTags(request.tags()),
            request.sourceType(),
            normalizeNullable(request.sourceRef()),
            request.confidence(),
            normalizeRequiredLowercase(request.lifecycleStage(), "lifecycleStage must not be blank"),
            normalizeMetadata(request.metadata()),
            request.expiresAt()
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

    private String normalizeNullableLowercase(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
    }

    private String normalizeRequiredLowercase(String value, String message) {
        requireNonBlank(value, message);
        return value.trim().toLowerCase(Locale.ROOT);
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
