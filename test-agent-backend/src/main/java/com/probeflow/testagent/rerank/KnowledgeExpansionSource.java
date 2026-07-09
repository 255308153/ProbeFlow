package com.probeflow.testagent.rerank;

import com.probeflow.testagent.knowledge.DocumentType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record KnowledgeExpansionSource(
    String chunkId,
    String documentId,
    String documentRevisionId,
    DocumentType documentType,
    String title,
    String content,
    String sourceRef,
    String parentIdentity,
    String heading,
    String entryKey,
    String businessEntity,
    String flowId,
    int chunkOrder,
    int tokenCost,
    KnowledgeExpansionSourceStatus status,
    boolean latest,
    Map<String, Object> metadata
) {
    public KnowledgeExpansionSource {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(documentRevisionId, "documentRevisionId must not be null");
        Objects.requireNonNull(documentType, "documentType must not be null");
        title = title == null ? "" : title;
        content = content == null ? "" : content;
        sourceRef = sourceRef == null ? "" : sourceRef;
        parentIdentity = hasText(parentIdentity) ? parentIdentity : documentId;
        heading = heading == null ? "" : heading;
        entryKey = entryKey == null ? "" : entryKey;
        businessEntity = businessEntity == null ? "" : businessEntity;
        flowId = flowId == null ? "" : flowId;
        tokenCost = Math.max(0, tokenCost);
        status = status == null ? KnowledgeExpansionSourceStatus.ACTIVE : status;
        metadata = metadata == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public boolean usable() {
        return latest && status == KnowledgeExpansionSourceStatus.ACTIVE;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
