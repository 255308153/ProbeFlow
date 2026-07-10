package com.probeflow.testagent.contractsmoke;

import java.util.List;
import java.util.Map;

record ContractEligibilityDecision(
    ContractSmokeOutcome outcome,
    Integer expectedStatus,
    String expectedContentType,
    Map<String, Object> contractOrigin,
    List<ContractSmokeDiagnostic> diagnostics
) {

    boolean eligible() {
        return outcome == null;
    }

    static ContractEligibilityDecision ready(
        Integer expectedStatus,
        String expectedContentType,
        Map<String, Object> contractOrigin
    ) {
        return new ContractEligibilityDecision(
            null,
            expectedStatus,
            expectedContentType,
            contractOrigin,
            List.of()
        );
    }

    static ContractEligibilityDecision terminal(
        ContractSmokeOutcome outcome,
        Map<String, Object> contractOrigin,
        ContractSmokeDiagnostic diagnostic
    ) {
        return new ContractEligibilityDecision(
            outcome,
            null,
            null,
            contractOrigin,
            List.of(diagnostic)
        );
    }
}
