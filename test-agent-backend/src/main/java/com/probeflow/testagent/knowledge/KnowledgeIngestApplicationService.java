package com.probeflow.testagent.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeIngestApplicationService {

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentRevisionRepository revisions;
    private final KnowledgeChunkRepository chunks;
    private final KnowledgeChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final int embeddingDimension;

    public KnowledgeIngestApplicationService(
        KnowledgeDocumentRepository documents,
        KnowledgeDocumentRevisionRepository revisions,
        KnowledgeChunkRepository chunks,
        KnowledgeChunkingService chunkingService,
        EmbeddingService embeddingService,
        ObjectMapper objectMapper,
        @Value("${probeflow.embedding.dimension:1024}") int embeddingDimension
    ) {
        this.documents = documents;
        this.revisions = revisions;
        this.chunks = chunks;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.embeddingDimension = embeddingDimension;
    }

    @Transactional
    public KnowledgeIngestResult ingest(KnowledgeIngestRequest request) {
        validate(request);

        var normalizedRequest = normalize(request);
        var sourceHash = sha256Hex(canonicalize(normalizedRequest));
        var existingDocument = documents.findBySourceTypeAndSourceRef(
            normalizedRequest.sourceType(),
            normalizedRequest.sourceRef()
        ).orElse(null);
        if (existingDocument == null) {
            return createDocumentAndRevision(normalizedRequest, sourceHash);
        }

        var latestRevision = revisions.findByDocumentIdAndLatestTrue(existingDocument.getDocumentId()).orElse(null);
        if (latestRevision != null && sourceHash.equals(latestRevision.getSourceHash())) {
            syncDocument(existingDocument, normalizedRequest);
            documents.save(existingDocument);
            return new KnowledgeIngestResult(
                existingDocument.getDocumentId(),
                latestRevision.getDocumentRevisionId(),
                latestRevision.getVersion(),
                false,
                false,
                true
            );
        }

        syncDocument(existingDocument, normalizedRequest);
        existingDocument = documents.save(existingDocument);
        if (latestRevision != null) {
            latestRevision.setLatest(false);
            latestRevision.setRevisionStatus(RevisionStatus.SUPERSEDED);
            revisions.save(latestRevision);
            supersedeChunks(existingDocument.getDocumentId(), latestRevision.getDocumentRevisionId());
        }

        var nextVersion = latestRevision == null ? 1 : latestRevision.getVersion() + 1;
        var revision = buildRevision(existingDocument.getDocumentId(), nextVersion, sourceHash, normalizedRequest);
        revision = revisions.save(revision);
        persistChunks(existingDocument.getDocumentId(), revision.getDocumentRevisionId(), normalizedRequest);

        return new KnowledgeIngestResult(
            existingDocument.getDocumentId(),
            revision.getDocumentRevisionId(),
            revision.getVersion(),
            false,
            true,
            false
        );
    }

    private KnowledgeIngestResult createDocumentAndRevision(KnowledgeIngestRequest request, String sourceHash) {
        var document = new KnowledgeDocument();
        syncDocument(document, request);
        document = documents.save(document);

        var revision = buildRevision(document.getDocumentId(), 1, sourceHash, request);
        revision = revisions.save(revision);
        persistChunks(document.getDocumentId(), revision.getDocumentRevisionId(), request);

        return new KnowledgeIngestResult(
            document.getDocumentId(),
            revision.getDocumentRevisionId(),
            revision.getVersion(),
            true,
            true,
            false
        );
    }

    private void syncDocument(KnowledgeDocument document, KnowledgeIngestRequest request) {
        document.setTitle(request.title());
        document.setSystemName(request.systemName());
        document.setModuleName(request.moduleName());
        document.setDocType(request.documentType());
        document.setBizEntity(request.bizEntity());
        document.setSourceType(request.sourceType());
        document.setSourceRef(request.sourceRef());
        document.setAuthority(request.authority());
        document.setStatus(DocumentStatus.ACTIVE);
        document.setRawContent(request.content());
        document.setMetadata(documentMetadata(request));
    }

    private KnowledgeDocumentRevision buildRevision(
        String documentId,
        int version,
        String sourceHash,
        KnowledgeIngestRequest request
    ) {
        var revision = new KnowledgeDocumentRevision();
        revision.setDocumentId(documentId);
        revision.setVersion(version);
        revision.setLatest(true);
        revision.setRevisionStatus(RevisionStatus.ACTIVE);
        revision.setSourceHash(sourceHash);
        revision.setMetadata(revisionMetadata(request));
        return revision;
    }

    private void supersedeChunks(String documentId, String documentRevisionId) {
        for (var chunk : chunks.findByDocumentIdAndDocumentRevisionId(documentId, documentRevisionId)) {
            chunk.setChunkStatus(ChunkStatus.SUPERSEDED);
            chunks.save(chunk);
        }
    }

    private void persistChunks(String documentId, String revisionId, KnowledgeIngestRequest request) {
        var generatedChunks = chunkingService.chunk(documentId, revisionId, request);
        validateEmbeddingDimension("provider", embeddingService.dimensions());
        for (var chunk : generatedChunks) {
            chunk.setEmbedding(embedDocumentChunk(chunk));
        }
        chunks.saveAll(generatedChunks);
    }

    private float[] embedDocumentChunk(KnowledgeChunk chunk) {
        var embedding = embeddingService.embedDocument(chunk.getChunkContent());
        return EmbeddingValidation.requireVector(
            "document",
            embeddingService.profile(),
            embedding,
            embeddingDimension
        );
    }

    private void validateEmbeddingDimension(String path, int actualDimension) {
        if (actualDimension != embeddingDimension) {
            throw new EmbeddingException(
                EmbeddingFailureCode.DIMENSION_MISMATCH,
                embeddingService.profile().profileId(),
                "embedding dimension mismatch for " + path
                    + ": expected " + embeddingDimension
                    + " but was " + actualDimension
            );
        }
    }

    private void validate(KnowledgeIngestRequest request) {
        requireNonBlank(request.title(), "title must not be blank");
        requireNonBlank(request.content(), "content must not be blank");
        requireNonBlank(request.sourceRef(), "sourceRef must not be blank");
        requireNonNull(request.contentFormat(), "contentFormat must be supported");
        requireNonNull(request.sourceType(), "sourceType must not be null");
        requireNonNull(request.documentType(), "documentType must not be null");
        requireNonNull(request.authority(), "authority must not be null");
        if (request.contentFormat() != KnowledgeContentFormat.MARKDOWN
            && request.contentFormat() != KnowledgeContentFormat.PLAIN_TEXT) {
            throw new IllegalArgumentException("contentFormat must be supported");
        }
    }

    private KnowledgeIngestRequest normalize(KnowledgeIngestRequest request) {
        return new KnowledgeIngestRequest(
            request.title().trim(),
            request.contentFormat(),
            request.content(),
            request.sourceType(),
            request.sourceRef().trim(),
            request.documentType(),
            request.authority(),
            normalizeNullable(request.systemName()),
            normalizeNullable(request.moduleName()),
            normalizeNullable(request.bizEntity()),
            normalizeList(request.tags()),
            normalizeList(request.applicableStages()),
            normalizeMap(request.metadata())
        );
    }

    private Map<String, Object> documentMetadata(KnowledgeIngestRequest request) {
        var metadata = new LinkedHashMap<>(request.metadata());
        metadata.put("contentFormat", request.contentFormat().name());
        metadata.put("tags", request.tags());
        metadata.put("applicableStages", request.applicableStages());
        return metadata;
    }

    private Map<String, Object> revisionMetadata(KnowledgeIngestRequest request) {
        var metadata = new LinkedHashMap<>(request.metadata());
        metadata.put("contentFormat", request.contentFormat().name());
        metadata.put("tags", request.tags());
        metadata.put("applicableStages", request.applicableStages());
        metadata.put("sourceRef", request.sourceRef());
        metadata.put("rawContent", request.content());
        return metadata;
    }

    private String canonicalize(KnowledgeIngestRequest request) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", request.title());
        payload.put("contentFormat", request.contentFormat().name());
        payload.put("content", request.content());
        payload.put("sourceType", request.sourceType().name());
        payload.put("sourceRef", request.sourceRef());
        payload.put("documentType", request.documentType().name());
        payload.put("authority", request.authority().name());
        payload.put("systemName", request.systemName());
        payload.put("moduleName", request.moduleName());
        payload.put("bizEntity", request.bizEntity());
        payload.put("tags", request.tags());
        payload.put("applicableStages", request.applicableStages());
        payload.put("metadata", request.metadata());
        try {
            return objectMapper.writeValueAsString(sortRecursively(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize knowledge ingest request", exception);
        }
    }

    private Object sortRecursively(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sortRecursively(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            var normalized = new ArrayList<>();
            for (var item : list) {
                normalized.add(sortRecursively(item));
            }
            return normalized;
        }
        return value;
    }

    private String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            var builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toList();
    }

    private Map<String, Object> normalizeMap(Map<String, Object> values) {
        if (values == null) {
            return Map.of();
        }
        var normalized = new TreeMap<String, Object>();
        values.forEach((key, value) -> normalized.put(key, value));
        return normalized;
    }

    private String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
    }
}
