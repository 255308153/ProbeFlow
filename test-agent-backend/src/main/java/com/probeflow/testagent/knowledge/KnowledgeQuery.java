package com.probeflow.testagent.knowledge;

import java.util.List;

public record KnowledgeQuery(
    String rawQuery,
    String systemName,
    String moduleName,
    String apiPath,
    String httpMethod,
    String bizEntity,
    DocumentType documentType,
    String applicableStage,
    List<String> tags,
    Integer limit,
    Integer tokenBudget
) {
}
