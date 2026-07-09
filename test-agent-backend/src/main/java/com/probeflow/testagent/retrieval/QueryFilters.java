package com.probeflow.testagent.retrieval;

import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.memory.MemoryFactType;
import java.util.List;

public record QueryFilters(
    String systemName,
    String moduleName,
    String apiPath,
    String httpMethod,
    String businessEntity,
    String errorCode,
    String failureClassification,
    String suiteId,
    String variableKey,
    String policyReason,
    String toolName,
    List<String> tags,
    List<DocumentType> documentTypes,
    List<MemoryFactType> factTypes
) {

    public QueryFilters {
        tags = tags == null ? List.of() : List.copyOf(tags);
        documentTypes = documentTypes == null ? List.of() : List.copyOf(documentTypes);
        factTypes = factTypes == null ? List.of() : List.copyOf(factTypes);
    }
}
