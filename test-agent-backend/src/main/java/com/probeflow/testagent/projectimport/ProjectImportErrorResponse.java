package com.probeflow.testagent.projectimport;

public record ProjectImportErrorResponse(
    String code,
    String category,
    String field,
    String message
) {

    static ProjectImportErrorResponse validation(String code, String field, String message) {
        return new ProjectImportErrorResponse(code, "VALIDATION", field, message);
    }

    static ProjectImportErrorResponse notFound(String code, String field, String message) {
        return new ProjectImportErrorResponse(code, "NOT_FOUND", field, message);
    }

    static ProjectImportErrorResponse boundary(String code, String field, String message) {
        return new ProjectImportErrorResponse(code, "BOUNDARY", field, message);
    }
}
