package com.probeflow.testagent.agentmemoryfeedback;

import com.probeflow.testagent.failureanalysis.SuiteFailureAnalysis;
import java.util.List;
import java.util.Map;

public record SuiteFailureMemoryFeedbackRequest(
    String taskId,
    String suiteId,
    String caseId,
    String executionId,
    SuiteFailureAnalysis suiteFailureAnalysis,
    String nextSuggestion,
    String recoveryActionType,
    boolean requiresHumanReview,
    Float confidence,
    List<String> evidence,
    Map<String, Object> metadata
) {

    public SuiteFailureMemoryFeedbackRequest {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
