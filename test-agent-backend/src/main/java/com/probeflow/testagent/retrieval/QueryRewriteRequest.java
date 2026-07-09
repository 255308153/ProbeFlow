package com.probeflow.testagent.retrieval;

import java.util.List;

public record QueryRewriteRequest(
    String stageProfile,
    String rawQuery,
    String userGoal,
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
    List<String> tags
) {
}
