package com.probeflow.testagent.contractsmoke;

import java.util.List;
import java.util.Map;

public record OpenApiContractSmokeRunResult(
    ContractSmokeOutcome outcome,
    String apiSpecId,
    String taskId,
    String draftId,
    String caseId,
    String executionRecordId,
    String profile,
    Integer expectedStatus,
    Integer actualStatus,
    Boolean statusCodeMatches,
    String expectedContentType,
    String actualContentType,
    Boolean contentTypeMatches,
    Map<String, Object> generatedRequest,
    Map<String, Object> contractOrigin,
    List<ContractSmokeDiagnostic> diagnostics
) {

    public OpenApiContractSmokeRunResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        generatedRequest = generatedRequest == null ? Map.of() : copyWithoutNullKeys(generatedRequest);
        contractOrigin = contractOrigin == null ? Map.of() : copyWithoutNullKeys(contractOrigin);
    }

    private static Map<String, Object> copyWithoutNullKeys(Map<String, Object> source) {
        var copy = new java.util.LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (key != null) {
                copy.put(key, value);
            }
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    static OpenApiContractSmokeRunResult terminal(
        ContractSmokeOutcome outcome,
        String apiSpecId,
        String profile,
        Map<String, Object> contractOrigin,
        List<ContractSmokeDiagnostic> diagnostics
    ) {
        return new OpenApiContractSmokeRunResult(
            outcome,
            apiSpecId,
            null,
            null,
            null,
            null,
            profile,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            contractOrigin,
            diagnostics
        );
    }
}
