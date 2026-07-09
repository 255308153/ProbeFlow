package com.probeflow.testagent.rerank;

import java.util.List;

public record RerankFeatureLedger(
    Double semantic,
    Double metadata,
    Double lexical,
    Double routeAgreement,
    Double exactEntity,
    Double stageFit,
    Double authority,
    Double freshness,
    Double memoryConfidence,
    Double memoryImportance,
    Double memorySuccessContribution,
    Double graphConfidence,
    Integer graphPathLength,
    boolean lowConfidence,
    List<String> conflictSignals,
    int tokenCost,
    List<RerankFeatureDiagnostic> diagnostics
) {
    public RerankFeatureLedger {
        conflictSignals = conflictSignals == null ? List.of() : List.copyOf(conflictSignals);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
