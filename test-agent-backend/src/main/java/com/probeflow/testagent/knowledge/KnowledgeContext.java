package com.probeflow.testagent.knowledge;

import java.util.List;

public record KnowledgeContext(
    List<KnowledgeContextEntry> businessRules,
    List<KnowledgeContextEntry> apiNotes,
    List<KnowledgeContextEntry> testSpecs,
    List<KnowledgeContextEntry> errorCodeGuides,
    List<KnowledgeContextEntry> environmentNotes,
    List<KnowledgeContextEntry> incidentHints,
    List<KnowledgeContextEntry> citedChunks,
    boolean lowConfidence,
    boolean lowCoverage
) {

    public static KnowledgeContext empty(boolean lowConfidence, boolean lowCoverage) {
        return new KnowledgeContext(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            lowConfidence,
            lowCoverage
        );
    }

    public boolean isEmpty() {
        return citedChunks.isEmpty();
    }
}
