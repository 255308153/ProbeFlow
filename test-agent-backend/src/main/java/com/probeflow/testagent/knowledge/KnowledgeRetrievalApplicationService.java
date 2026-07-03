package com.probeflow.testagent.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeRetrievalApplicationService {

    private static final int DEFAULT_LIMIT = 8;
    private static final int DEFAULT_TOKEN_BUDGET = 1200;

    private final KnowledgeChunkRepository chunks;
    private final KnowledgeDocumentRepository documents;

    public KnowledgeRetrievalApplicationService(
        KnowledgeChunkRepository chunks,
        KnowledgeDocumentRepository documents
    ) {
        this.chunks = chunks;
        this.documents = documents;
    }

    @Transactional(readOnly = true)
    public KnowledgeRetrievalResult retrieve(KnowledgeQuery query) {
        validate(query);
        var normalized = normalize(query);

        var candidates = chunks.findActiveLatestChunks(
            normalized.systemName(),
            normalized.moduleName(),
            normalized.bizEntity(),
            normalized.documentType()
        );
        if (candidates.isEmpty()) {
            return emptyResult(normalized.rawQuery());
        }

        var documentsById = loadDocuments(candidates);
        var filtered = candidates.stream()
            .filter(chunk -> matchesApiPath(chunk, normalized.apiPath()))
            .filter(chunk -> matchesHttpMethod(chunk, normalized.httpMethod()))
            .filter(chunk -> matchesStage(chunk, normalized.applicableStage()))
            .filter(chunk -> matchesTags(chunk, normalized.tags()))
            .map(chunk -> toHit(chunk, documentsById.get(chunk.getDocumentId())))
            .filter(Objects::nonNull)
            .toList();
        if (filtered.isEmpty()) {
            return emptyResult(normalized.rawQuery());
        }

        var constrained = applyLimitAndTokenBudget(filtered, normalized.limit(), normalized.tokenBudget());
        return new KnowledgeRetrievalResult(
            normalized.rawQuery(),
            constrained,
            constrained.isEmpty() ? 0.0d : 1.0d,
            filtered.size(),
            constrained.stream().mapToInt(KnowledgeRetrievalHit::tokenCount).sum()
        );
    }

    private void validate(KnowledgeQuery query) {
        requireNonBlank(query.rawQuery(), "rawQuery must not be blank");
        if (query.limit() != null && query.limit() <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (query.tokenBudget() != null && query.tokenBudget() <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
    }

    private KnowledgeQuery normalize(KnowledgeQuery query) {
        return new KnowledgeQuery(
            query.rawQuery().trim(),
            normalizeNullable(query.systemName()),
            normalizeNullable(query.moduleName()),
            normalizeNullable(query.apiPath()),
            normalizeUpper(query.httpMethod()),
            normalizeNullable(query.bizEntity()),
            query.documentType(),
            normalizeNullable(query.applicableStage()),
            normalizeTags(query.tags()),
            query.limit() == null ? DEFAULT_LIMIT : query.limit(),
            query.tokenBudget() == null ? DEFAULT_TOKEN_BUDGET : query.tokenBudget()
        );
    }

    private Map<String, KnowledgeDocument> loadDocuments(List<KnowledgeChunk> candidates) {
        var documentIds = candidates.stream()
            .map(KnowledgeChunk::getDocumentId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var documentsById = new LinkedHashMap<String, KnowledgeDocument>();
        for (var document : documents.findAllById(documentIds)) {
            documentsById.put(document.getDocumentId(), document);
        }
        return documentsById;
    }

    private KnowledgeRetrievalHit toHit(KnowledgeChunk chunk, KnowledgeDocument document) {
        if (document == null) {
            return null;
        }
        return new KnowledgeRetrievalHit(
            chunk.getChunkId(),
            chunk.getDocumentId(),
            chunk.getDocumentRevisionId(),
            chunk.getChunkTitle(),
            chunk.getChunkContent(),
            document.getSourceRef(),
            document.getDocType(),
            document.getSystemName(),
            document.getModuleName(),
            document.getBizEntity(),
            List.copyOf(chunk.getTags()),
            List.copyOf(chunk.getApplicableStages()),
            new LinkedHashMap<>(chunk.getMetadata()),
            chunk.getTokenCount()
        );
    }

    private List<KnowledgeRetrievalHit> applyLimitAndTokenBudget(
        List<KnowledgeRetrievalHit> hits,
        int limit,
        int tokenBudget
    ) {
        var constrained = new ArrayList<KnowledgeRetrievalHit>();
        var tokenCount = 0;
        for (var hit : hits) {
            if (constrained.size() >= limit) {
                break;
            }
            if (!constrained.isEmpty() && tokenCount + hit.tokenCount() > tokenBudget) {
                break;
            }
            constrained.add(hit);
            tokenCount += hit.tokenCount();
        }
        return constrained;
    }

    private boolean matchesApiPath(KnowledgeChunk chunk, String apiPath) {
        if (apiPath == null) {
            return true;
        }
        return metadataList(chunk, "apiPathHints").contains(apiPath);
    }

    private boolean matchesHttpMethod(KnowledgeChunk chunk, String httpMethod) {
        if (httpMethod == null) {
            return true;
        }
        return metadataList(chunk, "httpMethodHints").stream()
            .map(value -> value.toUpperCase(Locale.ROOT))
            .anyMatch(httpMethod::equals);
    }

    private boolean matchesStage(KnowledgeChunk chunk, String applicableStage) {
        return applicableStage == null || chunk.getApplicableStages().contains(applicableStage);
    }

    private boolean matchesTags(KnowledgeChunk chunk, List<String> tags) {
        return tags.isEmpty() || chunk.getTags().containsAll(tags);
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

    private KnowledgeRetrievalResult emptyResult(String rawQuery) {
        return new KnowledgeRetrievalResult(rawQuery, List.of(), 0.0d, 0, 0);
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeUpper(String value) {
        var normalized = normalizeNullable(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .distinct()
            .sorted()
            .toList();
    }
}
