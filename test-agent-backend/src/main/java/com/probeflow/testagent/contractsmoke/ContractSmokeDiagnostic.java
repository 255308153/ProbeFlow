package com.probeflow.testagent.contractsmoke;

public record ContractSmokeDiagnostic(
    String code,
    String severity,
    String message,
    String suggestedAction
) {

    public static ContractSmokeDiagnostic blocked(String code, String message, String suggestedAction) {
        return new ContractSmokeDiagnostic(code, "BLOCKED", message, suggestedAction);
    }

    public static ContractSmokeDiagnostic skipped(String code, String message, String suggestedAction) {
        return new ContractSmokeDiagnostic(code, "SKIPPED", message, suggestedAction);
    }

    public static ContractSmokeDiagnostic unsupported(String code, String message, String suggestedAction) {
        return new ContractSmokeDiagnostic(code, "UNSUPPORTED", message, suggestedAction);
    }

    public static ContractSmokeDiagnostic info(String code, String message) {
        return new ContractSmokeDiagnostic(code, "INFO", message, null);
    }
}
