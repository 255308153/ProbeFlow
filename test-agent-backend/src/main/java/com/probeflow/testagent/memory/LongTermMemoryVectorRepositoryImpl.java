package com.probeflow.testagent.memory;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.hibernate.Session;
import org.springframework.util.StringUtils;

public class LongTermMemoryVectorRepositoryImpl implements LongTermMemoryVectorRepository {

    private final EntityManager entityManager;

    public LongTermMemoryVectorRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<LongTermMemoryVectorCandidate> findPgvectorCandidates(
        List<MemoryScopeType> scopeTypes,
        String systemName,
        String moduleName,
        String apiPath,
        String errorCode,
        List<String> tags,
        String stageProfile,
        float[] queryEmbedding,
        int candidateLimit
    ) {
        if (!isPostgreSql()) {
            return findFallbackCandidates(
                scopeTypes,
                systemName,
                moduleName,
                apiPath,
                errorCode,
                tags,
                stageProfile,
                queryEmbedding,
                candidateLimit
            );
        }
        return findPostgresPgvectorCandidates(
            scopeTypes,
            systemName,
            moduleName,
            apiPath,
            errorCode,
            tags,
            queryEmbedding,
            candidateLimit
        );
    }

    private List<LongTermMemoryVectorCandidate> findPostgresPgvectorCandidates(
        List<MemoryScopeType> scopeTypes,
        String systemName,
        String moduleName,
        String apiPath,
        String errorCode,
        List<String> tags,
        float[] queryEmbedding,
        int candidateLimit
    ) {
        var sql = """
            select memory.memory_id, memory.embedding <=> cast(:queryEmbedding as vector) as vector_distance
            from long_term_memory memory
            where memory.status = 'ACTIVE'
              and (cast(:scopeTypesJson as jsonb) = '[]'::jsonb or cast(:scopeTypesJson as jsonb) ? memory.scope_type)
              and (:systemName is null or lower(memory.metadata ->> 'systemName') = lower(:systemName))
              and (:moduleName is null or lower(memory.metadata ->> 'module') = lower(:moduleName))
              and (:apiPath is null or lower(memory.metadata ->> 'apiPath') = lower(:apiPath))
              and (:errorCode is null or lower(memory.metadata ->> 'errorCode') = lower(:errorCode))
              and (cast(:tagsJson as jsonb) = '[]'::jsonb or memory.tags @> cast(:tagsJson as jsonb))
            order by vector_distance asc, memory.importance desc, memory.updated_at desc, memory.memory_id asc
            limit :candidateLimit
            """;

        @SuppressWarnings("unchecked")
        var rows = (List<Object[]>) entityManager.createNativeQuery(sql)
            .setParameter("queryEmbedding", vectorLiteral(queryEmbedding))
            .setParameter("scopeTypesJson", scopeTypesJson(scopeTypes))
            .setParameter("systemName", systemName)
            .setParameter("moduleName", moduleName)
            .setParameter("apiPath", apiPath)
            .setParameter("errorCode", errorCode)
            .setParameter("tagsJson", jsonArray(tags))
            .setParameter("candidateLimit", candidateLimit)
            .getResultList();

        var distancesByMemoryId = new LinkedHashMap<String, Double>();
        for (var row : rows) {
            distancesByMemoryId.put(String.valueOf(row[0]), ((Number) row[1]).doubleValue());
        }
        if (distancesByMemoryId.isEmpty()) {
            return List.of();
        }

        var memoriesById = new LinkedHashMap<String, LongTermMemory>();
        entityManager.createQuery(
                "select memory from LongTermMemory memory where memory.memoryId in :memoryIds",
                LongTermMemory.class
            )
            .setParameter("memoryIds", distancesByMemoryId.keySet())
            .getResultList()
            .forEach(memory -> memoriesById.put(memory.getMemoryId(), memory));

        var candidates = new ArrayList<LongTermMemoryVectorCandidate>();
        var rank = 1;
        for (var entry : distancesByMemoryId.entrySet()) {
            var memory = memoriesById.get(entry.getKey());
            if (memory != null) {
                candidates.add(new LongTermMemoryVectorCandidate(memory, entry.getValue(), rank++));
            }
        }
        return List.copyOf(candidates);
    }

    private List<LongTermMemoryVectorCandidate> findFallbackCandidates(
        List<MemoryScopeType> scopeTypes,
        String systemName,
        String moduleName,
        String apiPath,
        String errorCode,
        List<String> tags,
        String stageProfile,
        float[] queryEmbedding,
        int candidateLimit
    ) {
        var memories = entityManager.createQuery(
                "select memory from LongTermMemory memory where memory.status = :status",
                LongTermMemory.class
            )
            .setParameter("status", MemoryStatus.ACTIVE)
            .getResultList();

        var ranked = memories.stream()
            .filter(memory -> matchesScope(memory, scopeTypes))
            .filter(memory -> matchesMetadata(memory, "systemName", systemName))
            .filter(memory -> matchesMetadata(memory, "module", moduleName))
            .filter(memory -> matchesMetadata(memory, "apiPath", apiPath))
            .filter(memory -> matchesMetadata(memory, "errorCode", errorCode))
            .filter(memory -> tags == null || tags.isEmpty() || memory.getTags().containsAll(tags))
            .map(memory -> new ScoredMemory(memory, cosineDistance(queryEmbedding, memory.getEmbedding())))
            .sorted(Comparator
                .comparingDouble(ScoredMemory::distance)
                .thenComparing(scored -> stageTieBreaker(scored.memory(), stageProfile))
                .thenComparing(scored -> scored.memory().getMemoryId()))
            .limit(candidateLimit)
            .toList();

        var candidates = new ArrayList<LongTermMemoryVectorCandidate>();
        for (int index = 0; index < ranked.size(); index++) {
            var scored = ranked.get(index);
            candidates.add(new LongTermMemoryVectorCandidate(scored.memory(), scored.distance(), index + 1));
        }
        return List.copyOf(candidates);
    }

    private boolean isPostgreSql() {
        var session = entityManager.unwrap(Session.class);
        return session.doReturningWork(this::isPostgreSql);
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");
    }

    private boolean matchesScope(LongTermMemory memory, List<MemoryScopeType> scopeTypes) {
        return scopeTypes == null || scopeTypes.isEmpty() || scopeTypes.contains(memory.getScopeType());
    }

    private boolean matchesMetadata(LongTermMemory memory, String key, String expected) {
        if (!StringUtils.hasText(expected)) {
            return true;
        }
        var value = memory.getMetadata().get(key);
        return value != null && expected.equalsIgnoreCase(value.toString().trim());
    }

    private int stageTieBreaker(LongTermMemory memory, String stageProfile) {
        var stage = StringUtils.hasText(stageProfile) ? stageProfile.toLowerCase(Locale.ROOT) : "";
        if ("failure_analysis".equals(stage) && memory.getScopeType() == MemoryScopeType.FAILURE_PATTERN) {
            return 0;
        }
        if ("case_generation".equals(stage) && memory.getScopeType() == MemoryScopeType.TESTING_PATTERN) {
            return 0;
        }
        if ("report_generation".equals(stage) && memory.getScopeType() == MemoryScopeType.PREFERENCE) {
            return 0;
        }
        return 1;
    }

    private double cosineDistance(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            return 1.0d;
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 1.0d;
        }
        return 1.0d - (dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm)));
    }

    private String vectorLiteral(float[] vector) {
        var builder = new StringBuilder(vector.length * 8);
        builder.append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(Float.toString(vector[index]));
        }
        builder.append(']');
        return builder.toString();
    }

    private String scopeTypesJson(List<MemoryScopeType> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
            .map(MemoryScopeType::name)
            .map(this::jsonString)
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private String jsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
            .map(this::jsonString)
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record ScoredMemory(LongTermMemory memory, double distance) {
    }
}
