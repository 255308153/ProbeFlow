package com.probeflow.testagent.agentmemoryfeedback;

import java.util.List;
import java.util.Map;

public record AgentMemoryCandidateIntakeRequest(
    AgentMemoryCandidateSourceType sourceType,
    String sourceRef,
    String taskId,
    String summary,
    String content,
    String rawEvidence,
    List<String> tags,
    Float confidence,
    Map<String, Object> metadata
) {
}
