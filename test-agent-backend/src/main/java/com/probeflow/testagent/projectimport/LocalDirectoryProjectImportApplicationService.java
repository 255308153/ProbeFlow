package com.probeflow.testagent.projectimport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class LocalDirectoryProjectImportApplicationService {

    private static final String ALLOWED_ROOTS_PROPERTY =
        "probeflow.project-import.local-directory.allowed-roots";

    private final LocalDirectoryPathValidator pathValidator;

    @Autowired
    public LocalDirectoryProjectImportApplicationService(Environment environment) {
        this(new LocalDirectoryPathValidator(LocalDirectoryPathValidator.splitAllowedRoots(
            environment.getProperty(ALLOWED_ROOTS_PROPERTY, "")
        )));
    }

    LocalDirectoryProjectImportApplicationService(LocalDirectoryPathValidator pathValidator) {
        this.pathValidator = pathValidator;
    }

    public ProjectImportResponse importLocalDirectory(ProjectImportLocalDirectoryRequest request) {
        if (request == null) {
            throw new ProjectImportValidationException(ProjectImportErrorResponse.validation(
                "LOCAL_DIRECTORY_IMPORT_REQUEST_REQUIRED",
                "body",
                "Request body is required for local directory import."
            ));
        }

        pathValidator.validate(request.storagePath());
        return ProjectImportResponse.validatedLocalDirectory();
    }
}
