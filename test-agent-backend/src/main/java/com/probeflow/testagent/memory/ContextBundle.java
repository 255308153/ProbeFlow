package com.probeflow.testagent.memory;

import com.probeflow.testagent.knowledge.KnowledgeContext;
import java.util.List;
import java.util.Map;

public record ContextBundle(
    ApiContextSnapshot apiContext,
    TaskStateSnapshot taskState,
    List<SessionMemoryView> sessionContext,
    List<TaskMemoryView> taskMemory,
    KnowledgeContext knowledgeContext,
    LongTermMemoryRetrievalResult longTermMemoryContext,
    Map<String, Object> constraints,
    List<ContextCitation> citations,
    ContextCoverage coverage,
    ContextBudget budget
) {
}
