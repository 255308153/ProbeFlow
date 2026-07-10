package com.probeflow.testagent.projectimport;

class ProjectImportNotFoundException extends RuntimeException {

    private final ProjectImportErrorResponse error;

    ProjectImportNotFoundException(ProjectImportErrorResponse error) {
        super(error.message());
        this.error = error;
    }

    ProjectImportErrorResponse error() {
        return error;
    }
}
