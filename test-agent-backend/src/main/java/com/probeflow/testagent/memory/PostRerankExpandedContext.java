package com.probeflow.testagent.memory;

import com.probeflow.testagent.rerank.KnowledgeExpansionResult;
import com.probeflow.testagent.rerank.MemoryEvidenceExpansionResult;
import com.probeflow.testagent.rerank.RerankOutput;
import java.util.List;

public record PostRerankExpandedContext(
    RerankOutput rerankOutput,
    KnowledgeExpansionResult knowledgeExpansion,
    MemoryEvidenceExpansionResult memoryExpansion,
    List<String> diagnostics
) {

    public PostRerankExpandedContext {
        rerankOutput = rerankOutput == null ? new RerankOutput(List.of()) : rerankOutput;
        knowledgeExpansion = knowledgeExpansion == null
            ? new KnowledgeExpansionResult(List.of(), 0, 0, false)
            : knowledgeExpansion;
        memoryExpansion = memoryExpansion == null
            ? new MemoryEvidenceExpansionResult(List.of(), 0, 0, false, List.of())
            : memoryExpansion;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public boolean hasExpandedMaterial() {
        return !knowledgeExpansion.contexts().isEmpty() || !memoryExpansion.items().isEmpty();
    }

    public boolean hasRerankOutput() {
        return !rerankOutput.items().isEmpty();
    }
}
