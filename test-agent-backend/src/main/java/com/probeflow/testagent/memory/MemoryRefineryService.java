package com.probeflow.testagent.memory;

import com.probeflow.testagent.knowledge.EmbeddingService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MemoryRefineryService {

    private static final float MIN_CONFIDENCE = 0.55f;
    private static final int SUMMARY_LIMIT = 160;
    private static final int CONTENT_LIMIT = 280;

    private final LongTermMemoryRepository longTermMemories;
    private final EmbeddingService embeddingService;

    public MemoryRefineryService(LongTermMemoryRepository longTermMemories, EmbeddingService embeddingService) {
        this.longTermMemories = longTermMemories;
        this.embeddingService = embeddingService;
    }

    @Transactional
    public MemoryRefineryResult refine(MemoryCandidateRequest request) {
        validate(request);
        var normalized = normalize(request);
        var rejectionReason = rejectionReason(normalized);
        if (rejectionReason != null) {
            return new MemoryRefineryResult(false, false, false, rejectionReason, null);
        }

        var scopeType = classify(normalized);
        var summary = compactSummary(normalized);
        var content = compactContent(normalized);
        var fullContent = fullContent(normalized);
        var tags = deriveTags(normalized, scopeType);
        var metadata = buildMetadata(normalized, scopeType);
        var confidence = normalized.confidence();
        var importance = importanceOf(scopeType, confidence, tags, metadata);
        var successContribution = successContributionOf(scopeType, confidence);

        var existing = findMergeCandidate(scopeType, summary, content, fullContent, tags, normalized, metadata);
        if (existing.isPresent()) {
            var merged = mergeIntoExisting(
                existing.get(),
                normalized,
                scopeType,
                summary,
                content,
                fullContent,
                tags,
                metadata,
                confidence,
                importance,
                successContribution
            );
            return new MemoryRefineryResult(true, false, true, null, toView(merged));
        }

        var memory = new LongTermMemory();
        memory.setScopeType(scopeType);
        memory.setSummary(summary);
        memory.setContent(content);
        memory.setFullContent(fullContent);
        memory.setTags(tags);
        memory.setSourceType(normalized.sourceType());
        memory.setSourceRef(normalized.sourceRef());
        memory.setConfidence(confidence);
        memory.setImportance(importance);
        memory.setHitCount(0);
        memory.setSuccessContribution(successContribution);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setMetadata(metadata);
        memory.setEmbedding(embeddingService.embedDocument(summary + "\n" + content));

        var saved = longTermMemories.save(memory);
        return new MemoryRefineryResult(true, true, false, null, toView(saved));
    }

    @Transactional
    public void archiveMemory(String memoryId) {
        requireNonBlank(memoryId, "memoryId must not be blank");
        var memory = longTermMemories.findById(memoryId.trim())
            .orElseThrow(() -> new IllegalArgumentException("LongTermMemory not found: " + memoryId));
        memory.setStatus(MemoryStatus.ARCHIVED);
        memory.setArchivedAt(Instant.now());
        longTermMemories.save(memory);
    }

    @Transactional
    public void deactivateMemory(String memoryId) {
        requireNonBlank(memoryId, "memoryId must not be blank");
        var memory = longTermMemories.findById(memoryId.trim())
            .orElseThrow(() -> new IllegalArgumentException("LongTermMemory not found: " + memoryId));
        memory.setStatus(MemoryStatus.INACTIVE);
        longTermMemories.save(memory);
    }

    private RefinedMemoryView toView(LongTermMemory memory) {
        return new RefinedMemoryView(
            memory.getMemoryId(),
            memory.getScopeType(),
            memory.getSummary(),
            memory.getContent(),
            memory.getFullContent(),
            List.copyOf(memory.getTags()),
            memory.getSourceType(),
            memory.getSourceRef(),
            memory.getConfidence(),
            memory.getImportance(),
            memory.getSuccessContribution(),
            new LinkedHashMap<>(memory.getMetadata()),
            memory.getEmbedding().clone(),
            memory.getCreatedAt(),
            memory.getUpdatedAt()
        );
    }

    private boolean equivalent(
        LongTermMemory memory,
        MemoryScopeType scopeType,
        String summary,
        String content,
        List<String> tags,
        MemorySourceType sourceType,
        String sourceRef
    ) {
        return memory.getScopeType() == scopeType
            && Objects.equals(memory.getSummary(), summary)
            && Objects.equals(memory.getContent(), content)
            && Objects.equals(memory.getTags(), tags)
            && memory.getSourceType() == sourceType
            && Objects.equals(memory.getSourceRef(), sourceRef);
    }

    private java.util.Optional<LongTermMemory> findMergeCandidate(
        MemoryScopeType scopeType,
        String summary,
        String content,
        String fullContent,
        List<String> tags,
        MemoryCandidateRequest request,
        Map<String, Object> metadata
    ) {
        return longTermMemories.findAllByStatusOrderByCreatedAtAscMemoryIdAsc(MemoryStatus.ACTIVE)
            .stream()
            .filter(memory -> memory.getScopeType() == scopeType)
            .filter(memory -> equivalent(memory, scopeType, summary, content, tags, request.sourceType(), request.sourceRef())
                || isSimilarCandidate(memory, summary, content, fullContent, tags, request, metadata))
            .sorted(Comparator
                .comparing((LongTermMemory memory) -> !equivalent(memory, scopeType, summary, content, tags, request.sourceType(), request.sourceRef()))
                .thenComparing(LongTermMemory::getCreatedAt)
                .thenComparing(LongTermMemory::getMemoryId))
            .findFirst();
    }

    private LongTermMemory mergeIntoExisting(
        LongTermMemory existing,
        MemoryCandidateRequest request,
        MemoryScopeType scopeType,
        String summary,
        String content,
        String fullContent,
        List<String> tags,
        Map<String, Object> metadata,
        float confidence,
        float importance,
        float successContribution
    ) {
        existing.setSummary(preferLonger(existing.getSummary(), summary, SUMMARY_LIMIT));
        existing.setContent(preferLonger(existing.getContent(), content, CONTENT_LIMIT));
        existing.setFullContent(mergeEvidence(existing.getFullContent(), fullContent));
        existing.setTags(mergeTags(existing.getTags(), tags));
        existing.setConfidence(Math.max(existing.getConfidence(), confidence));
        existing.setImportance(Math.max(existing.getImportance(), importance));
        existing.setSuccessContribution(clamp(
            Math.max(existing.getSuccessContribution(), successContribution) + 0.02f,
            0.25f,
            0.85f
        ));
        existing.setHitCount(existing.getHitCount() + 1);
        existing.setMetadata(mergeMetadata(existing.getMetadata(), metadata, request.sourceRef()));
        existing.setEmbedding(embeddingService.embedDocument(existing.getSummary() + "\n" + existing.getContent()));
        return longTermMemories.save(existing);
    }

    private boolean isSimilarCandidate(
        LongTermMemory memory,
        String summary,
        String content,
        String fullContent,
        List<String> tags,
        MemoryCandidateRequest request,
        Map<String, Object> metadata
    ) {
        if (memory.getSourceType() != request.sourceType()) {
            return false;
        }

        var tagOverlap = overlapCount(memory.getTags(), tags);
        var metadataHintMatch = sharesMetadataHint(memory.getMetadata(), metadata);
        var textSimilarity = Math.max(
            tokenSimilarity(memory.getSummary(), summary),
            tokenSimilarity(memory.getContent(), content)
        );
        var evidenceContained = containsNormalized(memory.getFullContent(), fullContent)
            || containsNormalized(fullContent, memory.getFullContent());

        return evidenceContained
            || (metadataHintMatch && tagOverlap >= 1)
            || (metadataHintMatch && textSimilarity >= 0.30d)
            || (tagOverlap >= 2 && textSimilarity >= 0.45d);
    }

    private int overlapCount(List<String> left, List<String> right) {
        var leftSet = new HashSet<>(left);
        var overlap = 0;
        for (var candidate : right) {
            if (leftSet.contains(candidate)) {
                overlap++;
            }
        }
        return overlap;
    }

    private boolean sharesMetadataHint(Map<String, Object> left, Map<String, Object> right) {
        return metadataHintEquals(left, right, "errorCode")
            || metadataHintEquals(left, right, "apiPath")
            || metadataHintEquals(left, right, "module")
            || metadataHintEquals(left, right, "taskId");
    }

    private boolean metadataHintEquals(Map<String, Object> left, Map<String, Object> right, String key) {
        return left.containsKey(key)
            && right.containsKey(key)
            && Objects.equals(String.valueOf(left.get(key)), String.valueOf(right.get(key)));
    }

    private double tokenSimilarity(String left, String right) {
        var leftTokens = normalizedTokens(left);
        var rightTokens = normalizedTokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0d;
        }
        var intersection = 0;
        for (var token : leftTokens) {
            if (rightTokens.contains(token)) {
                intersection++;
            }
        }
        var union = new HashSet<String>();
        union.addAll(leftTokens);
        union.addAll(rightTokens);
        return intersection / (double) union.size();
    }

    private Set<String> normalizedTokens(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+"))
            .filter(token -> token.length() >= 3)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean containsNormalized(String container, String candidate) {
        return collapseWhitespace(container).contains(collapseWhitespace(candidate));
    }

    private String preferLonger(String existing, String candidate, int limit) {
        var normalizedExisting = collapseWhitespace(existing);
        var normalizedCandidate = collapseWhitespace(candidate);
        var preferred = normalizedCandidate.length() > normalizedExisting.length() ? normalizedCandidate : normalizedExisting;
        return limit(preferred, limit);
    }

    private String mergeEvidence(String existing, String incoming) {
        var normalizedExisting = collapseWhitespace(existing);
        var normalizedIncoming = collapseWhitespace(incoming);
        if (!StringUtils.hasText(normalizedExisting)) {
            return normalizedIncoming;
        }
        if (!StringUtils.hasText(normalizedIncoming) || normalizedExisting.contains(normalizedIncoming)) {
            return normalizedExisting;
        }
        if (normalizedIncoming.contains(normalizedExisting)) {
            return normalizedIncoming;
        }
        return normalizedExisting + "\n---\n" + normalizedIncoming;
    }

    private List<String> mergeTags(List<String> existing, List<String> incoming) {
        var merged = new LinkedHashSet<String>();
        merged.addAll(existing);
        merged.addAll(incoming);
        return merged.stream().sorted().toList();
    }

    private Map<String, Object> mergeMetadata(
        Map<String, Object> existing,
        Map<String, Object> incoming,
        String sourceRef
    ) {
        var merged = new TreeMap<String, Object>();
        merged.putAll(existing);
        incoming.forEach(merged::putIfAbsent);
        merged.put("mergeCount", asInt(existing.get("mergeCount")) + 1);
        merged.put("evidenceCount", asInt(existing.get("evidenceCount")) + 1);
        merged.put("lastMergedAt", Instant.now().toString());

        var sourceRefs = new LinkedHashSet<String>();
        collectSourceRefs(sourceRefs, existing.get("mergedSourceRefs"));
        collectSourceRefs(sourceRefs, existing.get("sourceRef"));
        collectSourceRefs(sourceRefs, sourceRef);
        if (!sourceRefs.isEmpty()) {
            merged.put("mergedSourceRefs", List.copyOf(sourceRefs));
        }
        return new LinkedHashMap<>(merged);
    }

    private void collectSourceRefs(LinkedHashSet<String> sourceRefs, Object raw) {
        if (raw instanceof List<?> values) {
            for (var value : values) {
                collectSourceRefs(sourceRefs, value);
            }
            return;
        }
        if (raw != null && StringUtils.hasText(raw.toString())) {
            sourceRefs.add(raw.toString().trim());
        }
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 1;
    }

    private String rejectionReason(MemoryCandidateRequest request) {
        var combined = combinedText(request);
        if (!StringUtils.hasText(request.summary()) && !StringUtils.hasText(request.content()) && !StringUtils.hasText(request.rawEvidence())) {
            return "empty-candidate";
        }
        if (request.confidence() < MIN_CONFIDENCE) {
            return "low-confidence";
        }
        if (containsAny(combined, List.of("one-off", "single run", "temporary", "scratch note"))) {
            return "one-off-noise";
        }
        if (containsAny(combined, List.of("task-local only", "for this task only", "do not reuse"))) {
            return "task-local-only";
        }
        if (request.sourceType() == MemorySourceType.TASK_STATE
            && request.taskId() != null
            && request.tags().isEmpty()
            && request.metadata().isEmpty()
            && compactContent(request).length() < 80) {
            return "task-local-only";
        }
        return null;
    }

    private MemoryScopeType classify(MemoryCandidateRequest request) {
        var combined = combinedText(request);
        if (request.sourceType() == MemorySourceType.USER_FEEDBACK
            || containsAny(combined, List.of("prefer", "preference", "terse", "compact", "concise"))) {
            return MemoryScopeType.PREFERENCE;
        }
        if (request.sourceType() == MemorySourceType.OBSERVATION
            || request.sourceType() == MemorySourceType.EXECUTION_RESULT
            || containsAny(combined, List.of("401", "403", "404", "500", "timeout", "retry", "failed", "failure", "error", "missing", "rejected"))) {
            return MemoryScopeType.FAILURE_PATTERN;
        }
        if (containsAny(combined, List.of("assert", "checklist", "setup", "precondition", "fixture", "test"))) {
            return MemoryScopeType.TESTING_PATTERN;
        }
        return MemoryScopeType.PROJECT_KNOWLEDGE;
    }

    private float importanceOf(
        MemoryScopeType scopeType,
        float confidence,
        List<String> tags,
        Map<String, Object> metadata
    ) {
        var base = switch (scopeType) {
            case FAILURE_PATTERN -> 0.72f;
            case TESTING_PATTERN -> 0.68f;
            case PROJECT_KNOWLEDGE -> 0.62f;
            case PREFERENCE -> 0.58f;
        };
        var tagBonus = Math.min(0.10f, tags.size() * 0.02f);
        var metadataBonus = metadata.containsKey("errorCode") || metadata.containsKey("apiPath") ? 0.05f : 0.0f;
        return clamp(base + (confidence - 0.5f) * 0.30f + tagBonus + metadataBonus, 0.50f, 0.95f);
    }

    private float successContributionOf(MemoryScopeType scopeType, float confidence) {
        var base = switch (scopeType) {
            case TESTING_PATTERN -> 0.55f;
            case PROJECT_KNOWLEDGE -> 0.50f;
            case FAILURE_PATTERN -> 0.44f;
            case PREFERENCE -> 0.36f;
        };
        return clamp(base + (confidence - 0.5f) * 0.15f, 0.25f, 0.75f);
    }

    private List<String> deriveTags(MemoryCandidateRequest request, MemoryScopeType scopeType) {
        var tags = new LinkedHashSet<String>();
        tags.addAll(request.tags());
        tags.add(scopeType.name().toLowerCase(Locale.ROOT));

        var combined = combinedText(request);
        addTagWhen(tags, combined.contains("auth"), "auth");
        addTagWhen(tags, combined.contains("tenant"), "tenant");
        addTagWhen(tags, combined.contains("payment"), "payment");
        addTagWhen(tags, combined.contains("order"), "order");
        addTagWhen(tags, combined.contains("retry"), "retry");
        addTagWhen(tags, combined.contains("timeout"), "timeout");
        addTagWhen(tags, combined.contains("assert"), "assertion");

        addMetadataTag(tags, request.metadata().get("module"));
        addMetadataTag(tags, request.metadata().get("errorCode"));

        return tags.stream().sorted().toList();
    }

    private Map<String, Object> buildMetadata(MemoryCandidateRequest request, MemoryScopeType scopeType) {
        var metadata = new TreeMap<String, Object>();
        metadata.putAll(request.metadata());
        metadata.put("refinedAt", Instant.now().toString());
        metadata.put("scopeType", scopeType.name());
        metadata.put("evidenceCount", 1);
        metadata.put("mergeCount", 1);
        if (request.sourceRef() != null) {
            metadata.put("sourceRef", request.sourceRef());
            metadata.put("mergedSourceRefs", List.of(request.sourceRef()));
        }
        if (request.taskId() != null) {
            metadata.put("taskId", request.taskId());
        }
        if (StringUtils.hasText(request.rawEvidence())) {
            metadata.put("evidenceLength", request.rawEvidence().length());
        }
        return new LinkedHashMap<>(metadata);
    }

    private String compactSummary(MemoryCandidateRequest request) {
        var base = StringUtils.hasText(request.summary()) ? request.summary().trim() : request.content().trim();
        return limit(collapseWhitespace(base), SUMMARY_LIMIT);
    }

    private String compactContent(MemoryCandidateRequest request) {
        var collapsed = collapseWhitespace(request.content());
        return limit(collapsed, CONTENT_LIMIT);
    }

    private String fullContent(MemoryCandidateRequest request) {
        if (StringUtils.hasText(request.rawEvidence())) {
            return collapseWhitespace(request.rawEvidence());
        }
        return collapseWhitespace(request.content());
    }

    private String combinedText(MemoryCandidateRequest request) {
        return String.join(
            " ",
            List.of(
                safeLower(request.summary()),
                safeLower(request.content()),
                safeLower(request.rawEvidence()),
                String.join(" ", request.tags())
            )
        );
    }

    private boolean containsAny(String input, List<String> tokens) {
        for (var token : tokens) {
            if (input.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private void addTagWhen(LinkedHashSet<String> tags, boolean condition, String tag) {
        if (condition) {
            tags.add(tag);
        }
    }

    private void addMetadataTag(LinkedHashSet<String> tags, Object value) {
        if (value == null) {
            return;
        }
        var normalized = collapseWhitespace(value.toString()).toLowerCase(Locale.ROOT);
        if (StringUtils.hasText(normalized)) {
            tags.add(normalized);
        }
    }

    private void validate(MemoryCandidateRequest request) {
        requireNonNull(request, "request must not be null");
        requireNonNull(request.sourceType(), "sourceType must not be null");
        requireNonNull(request.confidence(), "confidence must not be null");
        if (request.confidence() < 0.0f || request.confidence() > 1.0f) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
    }

    private MemoryCandidateRequest normalize(MemoryCandidateRequest request) {
        return new MemoryCandidateRequest(
            normalizeNullable(request.summary()),
            normalizeNullable(request.content()),
            request.sourceType(),
            normalizeNullable(request.sourceRef()),
            normalizeNullable(request.taskId()),
            normalizeTags(request.tags()),
            request.confidence(),
            normalizeNullable(request.rawEvidence()),
            normalizeMetadata(request.metadata())
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

    private String collapseWhitespace(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String limit(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3).trim() + "...";
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireNonBlank(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
