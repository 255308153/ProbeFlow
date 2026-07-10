package com.probeflow.testagent.projectimport;

public record ProjectImportLocalDirectoryRequest(
    String projectName,
    String storagePath,
    String requestedBy
) {
}
