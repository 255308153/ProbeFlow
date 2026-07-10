package com.probeflow.testagent.projectimport;

public class ProjectImportValidationException extends RuntimeException {

    private final ProjectImportErrorResponse error;

    ProjectImportValidationException(ProjectImportErrorResponse error) {
        super(error.message());
        this.error = error;
    }

    public ProjectImportErrorResponse error() {
        return error;
    }
}
