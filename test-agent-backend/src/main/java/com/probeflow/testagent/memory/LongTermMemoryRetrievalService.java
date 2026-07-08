package com.probeflow.testagent.memory;

import com.probeflow.testagent.knowledge.EmbeddingService;
import com.probeflow.testagent.knowledge.EmbeddingProfileMetadata;
import com.probeflow.testagent.knowledge.EmbeddingValidation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LongTermMemoryRetrievalService {

    private static final int DEFAULT_LIMIT = 6;
    private static final int DEFAULT_TOKEN_BUDGET = 800;

    private final LongTermMemoryRepository longTermMemories;
    private final EmbeddingService embeddingService;

    public LongTermMemoryRetrievalService(
        LongTermMemoryRepository longTermMemories,
        EmbeddingService embeddingService
    ) {
        this.longTermMemories = longTermMemories;
        this.embeddingService = embeddingService;
    }

    @Transactional
    public LongTermMemoryRetrievalResult retrieve(LongTermMemoryQuery query) {
        validate(query);
        var normalized = normalize(query);
        var queryEmbedding = embeddingService.embedQuery(normalized.rawQuery());
        validateEmbedding(queryEmbedding);
        var candidateLimit = Math.max(normalized.limit() * 4, 24);

        var candidates = longTermMemories.findPgvectorCandidates(
            normalized.scopeTypes(),
            normalized.systemName(),
            normalized.moduleName(),
            normalized.apiPath(),
            normalized.errorCode(),
            normalized.tags(),
            normalized.stageProfile(),
            queryEmbedding,
            candidateLimit
        );
        if (candidates.isEmpty()) {
            return new LongTermMemoryRetrievalResult(List.of(), 0, 0);
        }

        var newest = candidates.stream().mapToLong(candidate -> candidate.memory().getUpdatedAt().toEpochMilli()).max().orElse(0L);
        var oldest = candidates.stream().mapToLong(candidate -> candidate.memory().getUpdatedAt().toEpochMilli()).min().orElse(newest);

        var ranked = candidates.stream()
            .map(candidate -> score(candidate, normalized, newest, oldest))
            .sorted(Comparator
                .comparingDouble(LongTermMemoryRetrievalHit::score).reversed()
                .thenComparing(LongTermMemoryRetrievalHit::summary)
                .thenComparing(LongTermMemoryRetrievalHit::memoryId))
            .toList();

        var selected = applyLimitAndBudget(ranked, normalized.limit(), normalized.tokenBudget());
        selected = touchSelected(selected);
        return new LongTermMemoryRetrievalResult(
            selected,
            ranked.size(),
            selected.stream().mapToInt(LongTermMemoryRetrievalHit::tokenCount).sum()
        );
    }

    private LongTermMemoryRetrievalHit score(
        LongTermMemoryVectorCandidate candidate,
        LongTermMemoryQuery query,
        long newest,
        long oldest
    ) {
        var memory = candidate.memory();
        var structure = structureScore(memory, query);
        var tag = tagScore(memory, query);
        var metadata = new LinkedHashMap<>(
            EmbeddingProfileMetadata.withReindexStatus(memory.getMetadata(), embeddingService.profile())
        );
        metadata.put("retrievalChannel", "pgvector");
        metadata.put("vectorDistance", candidate.vectorDistance());
        metadata.put("candidateRank", candidate.candidateRank());
        var profileCompatible = EmbeddingProfileMetadata.isCompatible(metadata, embeddingService.profile());
        var vector = profileCompatible ? semanticScore(memory, query, candidate.vectorDistance()) : 0.0d;
        var importance = memory.getImportance();
        var confidence = memory.getConfidence();
        var success = memory.getSuccessContribution();
        var hitCount = Math.min(1.0d, memory.getHitCount() / 5.0d);
        var freshness = freshnessScore(memory, newest, oldest);
        var stageFit = stageFitScore(memory, query.stageProfile());
        var weighted = weightedScores(query.stageProfile(), structure, tag, vector, importance, confidence, success, hitCount, freshness, stageFit);
        var score = weighted.values().stream().mapToDouble(Double::doubleValue).sum();
        var reasons = new ArrayList<String>();
        addReason(reasons, structure >= 1.0d, "structure-match");
        addReason(reasons, tag >= 0.5d, "tag-match");
        addReason(reasons, vector >= 0.45d, "semantic-match");
        addReason(reasons, stageFit >= 0.9d, "stage-fit");
        addReason(reasons, !profileCompatible, "reindex-required");

        return new LongTermMemoryRetrievalHit(
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
            memory.getHitCount(),
            memory.getLastUsedAt(),
            Map.copyOf(metadata),
            estimateTokens(memory),
            score,
            weighted,
            reasons,
            !profileCompatible || score < 0.35d
        );
    }

    private List<LongTermMemoryRetrievalHit> touchSelected(List<LongTermMemoryRetrievalHit> hits) {
        var now = Instant.now();
        var updatedHits = new ArrayList<LongTermMemoryRetrievalHit>(hits.size());
        for (var hit : hits) {
            var memory = longTermMemories.findById(hit.memoryId()).orElseThrow();
            memory.setHitCount(memory.getHitCount() + 1);
            memory.setLastUsedAt(now);
            longTermMemories.save(memory);
            var metadata = new LinkedHashMap<>(hit.metadata());
            metadata.put("selectedAt", now.toString());
            updatedHits.add(new LongTermMemoryRetrievalHit(
                hit.memoryId(),
                hit.scopeType(),
                hit.summary(),
                hit.content(),
                hit.fullContent(),
                hit.tags(),
                hit.sourceType(),
                hit.sourceRef(),
                hit.confidence(),
                hit.importance(),
                hit.successContribution(),
                memory.getHitCount(),
                now,
                metadata,
                hit.tokenCount(),
                hit.score(),
                hit.componentScores(),
                hit.matchReasons(),
                hit.lowConfidence()
            ));
        }
        return List.copyOf(updatedHits);
    }

    private List<LongTermMemoryRetrievalHit> applyLimitAndBudget(List<LongTermMemoryRetrievalHit> ranked, int limit, int tokenBudget) {
        var selected = new ArrayList<LongTermMemoryRetrievalHit>();
        var tokens = 0;
        for (var hit : ranked) {
            if (selected.size() >= limit) {
                break;
            }
            if (tokens + hit.tokenCount() > tokenBudget) {
                continue;
            }
            selected.add(hit);
            tokens += hit.tokenCount();
        }
        return selected;
    }

    private double structureScore(LongTermMemory memory, LongTermMemoryQuery query) {
        double score = 0.0d;
        score += scoreMetadata(memory, "systemName", query.systemName(), 0.25d);
        score += scoreMetadata(memory, "module", query.moduleName(), 0.30d);
        score += scoreMetadata(memory, "apiPath", query.apiPath(), 0.30d);
        score += scoreMetadata(memory, "errorCode", query.errorCode(), 0.30d);
        return Math.min(1.0d, score);
    }

    private double scoreMetadata(LongTermMemory memory, String key, String expected, double weight) {
        if (!StringUtils.hasText(expected)) {
            return 0.0d;
        }
        var value = memory.getMetadata().get(key);
        return value != null && expected.equalsIgnoreCase(value.toString().trim()) ? weight : 0.0d;
    }

    private double tagScore(LongTermMemory memory, LongTermMemoryQuery query) {
        if (query.tags().isEmpty()) {
            return 0.0d;
        }
        var memoryTags = new LinkedHashSet<>(memory.getTags());
        var matches = 0;
        for (var tag : query.tags()) {
            if (memoryTags.contains(tag)) {
                matches++;
            }
        }
        return matches / (double) query.tags().size();
    }

    private double semanticScore(LongTermMemory memory, LongTermMemoryQuery query, double vectorDistance) {
        var embeddingSimilarity = Math.max(0.0d, 1.0d - vectorDistance);
        var lexicalSimilarity = Math.max(
            tokenSimilarity(query.rawQuery(), memory.getSummary()),
            tokenSimilarity(query.rawQuery(), memory.getContent())
        );
        return Math.max(embeddingSimilarity, lexicalSimilarity);
    }

    private double stageFitScore(LongTermMemory memory, String stageProfile) {
        return switch (normalizeStage(stageProfile)) {
            case "failure_analysis" -> memory.getScopeType() == MemoryScopeType.FAILURE_PATTERN ? 1.0d
                : (memory.getScopeType() == MemoryScopeType.PROJECT_KNOWLEDGE ? 0.55d : 0.35d);
            case "case_generation" -> memory.getScopeType() == MemoryScopeType.TESTING_PATTERN ? 1.0d
                : (memory.getScopeType() == MemoryScopeType.PROJECT_KNOWLEDGE ? 0.8d : 0.35d);
            case "execution_preparation" -> memory.getScopeType() == MemoryScopeType.PROJECT_KNOWLEDGE ? 1.0d
                : (memory.getScopeType() == MemoryScopeType.FAILURE_PATTERN ? 0.75d : 0.40d);
            case "report_generation" -> memory.getScopeType() == MemoryScopeType.PREFERENCE ? 1.0d
                : (memory.getScopeType() == MemoryScopeType.FAILURE_PATTERN ? 0.7d : 0.45d);
            default -> 0.5d;
        };
    }

    private Map<String, Double> weightedScores(
        String stageProfile,
        double structure,
        double tag,
        double vector,
        double importance,
        double confidence,
        double success,
        double hitCount,
        double freshness,
        double stageFit
    ) {
        var weights = stageWeights(stageProfile);
        var weighted = new LinkedHashMap<String, Double>();
        weighted.put("structure", structure * weights.get("structure"));
        weighted.put("tag", tag * weights.get("tag"));
        weighted.put("vector", vector * weights.get("vector"));
        weighted.put("importance", importance * weights.get("importance"));
        weighted.put("confidence", confidence * weights.get("confidence"));
        weighted.put("successContribution", success * weights.get("success"));
        weighted.put("hitCount", hitCount * weights.get("hitCount"));
        weighted.put("freshness", freshness * weights.get("freshness"));
        weighted.put("stageFit", stageFit * weights.get("stageFit"));
        return weighted;
    }

    private Map<String, Double> stageWeights(String stageProfile) {
        var stage = normalizeStage(stageProfile);
        if ("failure_analysis".equals(stage)) {
            return Map.of("structure", 0.12d, "tag", 0.16d, "vector", 0.16d, "importance", 0.10d,
                "confidence", 0.10d, "success", 0.10d, "hitCount", 0.06d, "freshness", 0.08d, "stageFit", 0.12d);
        }
        if ("case_generation".equals(stage)) {
            return Map.of("structure", 0.14d, "tag", 0.12d, "vector", 0.14d, "importance", 0.10d,
                "confidence", 0.08d, "success", 0.10d, "hitCount", 0.08d, "freshness", 0.06d, "stageFit", 0.18d);
        }
        if ("execution_preparation".equals(stage)) {
            return Map.of("structure", 0.18d, "tag", 0.14d, "vector", 0.12d, "importance", 0.10d,
                "confidence", 0.10d, "success", 0.08d, "hitCount", 0.06d, "freshness", 0.07d, "stageFit", 0.15d);
        }
        if ("report_generation".equals(stage)) {
            return Map.of("structure", 0.10d, "tag", 0.12d, "vector", 0.10d, "importance", 0.10d,
                "confidence", 0.10d, "success", 0.12d, "hitCount", 0.08d, "freshness", 0.08d, "stageFit", 0.20d);
        }
        return Map.of("structure", 0.14d, "tag", 0.14d, "vector", 0.14d, "importance", 0.10d,
            "confidence", 0.10d, "success", 0.10d, "hitCount", 0.06d, "freshness", 0.08d, "stageFit", 0.14d);
    }

    private double freshnessScore(LongTermMemory memory, long newest, long oldest) {
        if (newest <= oldest) {
            return 1.0d;
        }
        return (memory.getUpdatedAt().toEpochMilli() - oldest) / (double) (newest - oldest);
    }

    private double tokenSimilarity(String left, String right) {
        var leftTokens = normalizedTokens(left);
        var rightTokens = normalizedTokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0d;
        }
        var matches = 0;
        for (var token : leftTokens) {
            if (rightTokens.contains(token)) {
                matches++;
            }
        }
        return matches / (double) Math.max(leftTokens.size(), rightTokens.size());
    }

    private Set<String> normalizedTokens(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        var tokens = new LinkedHashSet<String>();
        for (var token : value.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private int estimateTokens(LongTermMemory memory) {
        var text = memory.getSummary() + "\n" + memory.getContent();
        var normalized = text.trim();
        if (normalized.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(normalized.length() / 24.0d));
    }

    private void addReason(List<String> reasons, boolean condition, String reason) {
        if (condition) {
            reasons.add(reason);
        }
    }

    private void validate(LongTermMemoryQuery query) {
        if (!StringUtils.hasText(query.rawQuery())) {
            throw new IllegalArgumentException("rawQuery must not be blank");
        }
        if (query.limit() != null && query.limit() <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (query.tokenBudget() != null && query.tokenBudget() <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
    }

    private void validateEmbedding(float[] embedding) {
        EmbeddingValidation.requireVector(
            "query",
            embeddingService.profile(),
            embedding,
            embeddingService.dimensions()
        );
    }

    private LongTermMemoryQuery normalize(LongTermMemoryQuery query) {
        return new LongTermMemoryQuery(
            normalizeStage(query.stageProfile()),
            query.rawQuery().trim(),
            normalizeNullable(query.systemName()),
            normalizeNullable(query.moduleName()),
            normalizeNullable(query.apiPath()),
            normalizeNullable(query.errorCode()),
            normalizeTags(query.tags()),
            query.scopeTypes() == null ? List.of() : List.copyOf(query.scopeTypes()),
            query.limit() == null ? DEFAULT_LIMIT : query.limit(),
            query.tokenBudget() == null ? DEFAULT_TOKEN_BUDGET : query.tokenBudget()
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

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeStage(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "general";
    }
}
