package com.probeflow.testagent.knowledge;

import java.util.List;
import java.util.Map;

public record KnowledgeIngestRequest(
    String title,
    KnowledgeContentFormat contentFormat,
    String content,
    DocumentSourceType sourceType,
    String sourceRef,
    DocumentType documentType,
    DocumentAuthority authority,
    String systemName,
    String moduleName,
    String bizEntity,
    List<String> tags,
    List<String> applicableStages,
    Map<String, Object> metadata
) {
}
