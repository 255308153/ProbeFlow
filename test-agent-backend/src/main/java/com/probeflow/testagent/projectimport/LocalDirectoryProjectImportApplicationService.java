package com.probeflow.testagent.projectimport;

import com.probeflow.testagent.analysis.ApiAnalysisApplicationService;
import com.probeflow.testagent.analysis.ApiAnalysisRequest;
import com.probeflow.testagent.analysis.ApiAnalysisResult;
import com.probeflow.testagent.sourcematerial.MaterialType;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class LocalDirectoryProjectImportApplicationService {

    private static final String ALLOWED_ROOTS_PROPERTY =
        "probeflow.project-import.local-directory.allowed-roots";

    private final LocalDirectoryPathValidator pathValidator;
    private final ApiAnalysisApplicationService apiAnalysis;

    @Autowired
    public LocalDirectoryProjectImportApplicationService(
        Environment environment,
        ApiAnalysisApplicationService apiAnalysis
    ) {
        this(new LocalDirectoryPathValidator(LocalDirectoryPathValidator.splitAllowedRoots(
            environment.getProperty(ALLOWED_ROOTS_PROPERTY, "")
        )), apiAnalysis);
    }

    LocalDirectoryProjectImportApplicationService(LocalDirectoryPathValidator pathValidator) {
        this(pathValidator, null);
    }

    LocalDirectoryProjectImportApplicationService(
        LocalDirectoryPathValidator pathValidator,
        ApiAnalysisApplicationService apiAnalysis
    ) {
        this.pathValidator = pathValidator;
        this.apiAnalysis = apiAnalysis;
    }

    public ProjectImportResponse importLocalDirectory(ProjectImportLocalDirectoryRequest request) {
        if (request == null) {
            throw new ProjectImportValidationException(ProjectImportErrorResponse.validation(
                "LOCAL_DIRECTORY_IMPORT_REQUEST_REQUIRED",
                "body",
                "Request body is required for local directory import."
            ));
        }

        var realPath = pathValidator.validate(request.storagePath());
        if (apiAnalysis == null) {
            return ProjectImportResponse.validatedLocalDirectory();
        }

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.SOURCE_DIRECTORY,
            request.projectName(),
            request.storagePath(),
            storagePath(realPath),
            request.requestedBy()
        ));
        return toProjectImportResponse(result);
    }

    private ProjectImportResponse toProjectImportResponse(ApiAnalysisResult result) {
        if (result.succeeded()) {
            return ProjectImportResponse.readySourceDirectory(
                result.materialId(),
                result.taskId(),
                result.apiSpecIds().size()
            );
        }

        return ProjectImportResponse.failedSourceDirectory(
            result.materialId(),
            result.taskId(),
            ProjectImportDiagnostic.blocker(
                result.errorCode(),
                "Local directory analysis failed",
                result.errorMessage(),
                "Check that the directory contains analyzable Spring @RestController source code."
            )
        );
    }

    private String storagePath(Path realPath) {
        return realPath.toString();
    }
}
