package com.probeflow.testagent.knowledge;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.hibernate.Session;

public class KnowledgeChunkVectorRepositoryImpl implements KnowledgeChunkVectorRepository {

    private final EntityManager entityManager;

    public KnowledgeChunkVectorRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<KnowledgeVectorCandidate> findPgvectorCandidates(
        String systemName,
        String moduleName,
        String bizEntity,
        DocumentType documentType,
        String apiPath,
        String httpMethod,
        String applicableStage,
        List<String> tags,
        float[] queryEmbedding,
        int candidateLimit
    ) {
        if (!isPostgreSql()) {
            return findFallbackCandidates(
                systemName,
                moduleName,
                bizEntity,
                documentType,
                apiPath,
                httpMethod,
                applicableStage,
                tags,
                queryEmbedding,
                candidateLimit
            );
        }
        return findPostgresPgvectorCandidates(
            systemName,
            moduleName,
            bizEntity,
            documentType,
            apiPath,
            httpMethod,
            applicableStage,
            tags,
            queryEmbedding,
            candidateLimit
        );
    }

    private List<KnowledgeVectorCandidate> findPostgresPgvectorCandidates(
        String systemName,
        String moduleName,
        String bizEntity,
        DocumentType documentType,
        String apiPath,
        String httpMethod,
        String applicableStage,
        List<String> tags,
        float[] queryEmbedding,
        int candidateLimit
    ) {
        var sql = """
            select chunk.chunk_id, chunk.embedding <=> cast(:queryEmbedding as vector) as vector_distance
            from knowledge_chunk chunk
            join knowledge_document_revision revision
              on chunk.document_revision_id = revision.document_revision_id
            join knowledge_document document
              on chunk.document_id = document.document_id
            where chunk.chunk_status = 'ACTIVE'
              and revision.latest = true
              and revision.revision_status = 'ACTIVE'
              and document.status = 'ACTIVE'
              and (:systemName is null or document.system_name = :systemName)
              and (:moduleName is null or document.module_name = :moduleName)
              and (:bizEntity is null or document.biz_entity = :bizEntity)
              and (:documentType is null or document.doc_type = :documentType)
              and (:apiPath is null or jsonb_exists(chunk.metadata -> 'apiPathHints', :apiPath))
              and (:httpMethod is null or jsonb_exists(chunk.metadata -> 'httpMethodHints', :httpMethod))
              and (:applicableStage is null or jsonb_exists(chunk.applicable_stages, :applicableStage))
              and (cast(:tagsJson as jsonb) = '[]'::jsonb or chunk.tags @> cast(:tagsJson as jsonb))
            order by vector_distance asc, document.updated_at desc, chunk.chunk_order asc
            limit :candidateLimit
            """;

        @SuppressWarnings("unchecked")
        var rows = (List<Object[]>) entityManager.createNativeQuery(sql)
            .setParameter("queryEmbedding", vectorLiteral(queryEmbedding))
            .setParameter("systemName", systemName)
            .setParameter("moduleName", moduleName)
            .setParameter("bizEntity", bizEntity)
            .setParameter("documentType", documentType == null ? null : documentType.name())
            .setParameter("apiPath", apiPath)
            .setParameter("httpMethod", httpMethod)
            .setParameter("applicableStage", applicableStage)
            .setParameter("tagsJson", jsonArray(tags))
            .setParameter("candidateLimit", candidateLimit)
            .getResultList();

        var distancesByChunkId = new LinkedHashMap<String, Double>();
        for (var row : rows) {
            distancesByChunkId.put(String.valueOf(row[0]), ((Number) row[1]).doubleValue());
        }
        if (distancesByChunkId.isEmpty()) {
            return List.of();
        }

        var chunksById = new LinkedHashMap<String, KnowledgeChunk>();
        entityManager.createQuery(
                "select chunk from KnowledgeChunk chunk where chunk.chunkId in :chunkIds",
                KnowledgeChunk.class
            )
            .setParameter("chunkIds", distancesByChunkId.keySet())
            .getResultList()
            .forEach(chunk -> chunksById.put(chunk.getChunkId(), chunk));

        var candidates = new ArrayList<KnowledgeVectorCandidate>();
        var rank = 1;
        for (var entry : distancesByChunkId.entrySet()) {
            var chunk = chunksById.get(entry.getKey());
            if (chunk != null) {
                candidates.add(new KnowledgeVectorCandidate(chunk, entry.getValue(), rank++));
            }
        }
        return List.copyOf(candidates);
    }

    private List<KnowledgeVectorCandidate> findFallbackCandidates(
        String systemName,
        String moduleName,
        String bizEntity,
        DocumentType documentType,
        String apiPath,
        String httpMethod,
        String applicableStage,
        List<String> tags,
        float[] queryEmbedding,
        int candidateLimit
    ) {
        var jpql = """
            select chunk
            from KnowledgeChunk chunk, KnowledgeDocumentRevision revision, KnowledgeDocument document
            where chunk.documentRevisionId = revision.documentRevisionId
              and chunk.documentId = document.documentId
              and chunk.chunkStatus = com.probeflow.testagent.knowledge.ChunkStatus.ACTIVE
              and revision.latest = true
              and revision.revisionStatus = com.probeflow.testagent.knowledge.RevisionStatus.ACTIVE
              and document.status = com.probeflow.testagent.knowledge.DocumentStatus.ACTIVE
              and (:systemName is null or document.systemName = :systemName)
              and (:moduleName is null or document.moduleName = :moduleName)
              and (:bizEntity is null or document.bizEntity = :bizEntity)
              and (:documentType is null or document.docType = :documentType)
            """;
        var chunks = entityManager.createQuery(jpql, KnowledgeChunk.class)
            .setParameter("systemName", systemName)
            .setParameter("moduleName", moduleName)
            .setParameter("bizEntity", bizEntity)
            .setParameter("documentType", documentType)
            .getResultList();

        var ranked = chunks.stream()
            .filter(chunk -> matchesApiPath(chunk, apiPath))
            .filter(chunk -> matchesHttpMethod(chunk, httpMethod))
            .filter(chunk -> applicableStage == null || chunk.getApplicableStages().contains(applicableStage))
            .filter(chunk -> tags == null || tags.isEmpty() || chunk.getTags().containsAll(tags))
            .map(chunk -> new ScoredChunk(chunk, cosineDistance(queryEmbedding, chunk.getEmbedding())))
            .sorted(Comparator
                .comparingDouble(ScoredChunk::distance)
                .thenComparing(scored -> scored.chunk().getChunkOrder()))
            .limit(candidateLimit)
            .toList();

        var candidates = new ArrayList<KnowledgeVectorCandidate>();
        for (int index = 0; index < ranked.size(); index++) {
            var scored = ranked.get(index);
            candidates.add(new KnowledgeVectorCandidate(scored.chunk(), scored.distance(), index + 1));
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

    private boolean matchesApiPath(KnowledgeChunk chunk, String apiPath) {
        return apiPath == null || metadataList(chunk, "apiPathHints").contains(apiPath);
    }

    private boolean matchesHttpMethod(KnowledgeChunk chunk, String httpMethod) {
        return httpMethod == null || metadataList(chunk, "httpMethodHints").stream()
            .map(value -> value.toUpperCase(Locale.ROOT))
            .anyMatch(httpMethod::equals);
    }

    @SuppressWarnings("unchecked")
    private List<String> metadataList(KnowledgeChunk chunk, String key) {
        var value = chunk.getMetadata().get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        }
        return List.of();
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

    private record ScoredChunk(KnowledgeChunk chunk, double distance) {
    }
}
