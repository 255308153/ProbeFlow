package com.probeflow.testagent.projectimport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class LocalDirectoryPathValidator {

    private final List<Path> allowedRoots;

    LocalDirectoryPathValidator(List<String> configuredAllowedRoots) {
        this.allowedRoots = configuredAllowedRoots.stream()
            .filter(root -> root != null && !root.isBlank())
            .map(String::trim)
            .flatMap(root -> realAllowedRoot(root).stream())
            .distinct()
            .toList();
    }

    Path validate(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw validationError(
                "LOCAL_DIRECTORY_PATH_REQUIRED",
                "storagePath is required and must be a service-side absolute directory path."
            );
        }

        Path requestedPath;
        try {
            requestedPath = Path.of(storagePath.trim());
        } catch (InvalidPathException exception) {
            throw validationError(
                "LOCAL_DIRECTORY_PATH_INVALID",
                "storagePath is not a valid local filesystem path."
            );
        }

        if (!requestedPath.isAbsolute()) {
            throw validationError(
                "LOCAL_DIRECTORY_PATH_NOT_ABSOLUTE",
                "storagePath must be an absolute path on the ProbeFlow server machine."
            );
        }

        Path realPath;
        try {
            realPath = requestedPath.toRealPath();
        } catch (NoSuchFileException exception) {
            throw validationError(
                "LOCAL_DIRECTORY_PATH_NOT_FOUND",
                "storagePath does not point to an existing directory."
            );
        } catch (IOException exception) {
            throw validationError(
                "LOCAL_DIRECTORY_PATH_NOT_READABLE",
                "storagePath cannot be read by the ProbeFlow server process."
            );
        }

        if (allowedRoots.isEmpty()) {
            throw validationError(
                "LOCAL_DIRECTORY_ALLOWED_ROOTS_REQUIRED",
                "Local directory import is disabled until allowed server root directories are configured."
            );
        }

        if (allowedRoots.stream().noneMatch(root -> realPath.equals(root) || realPath.startsWith(root))) {
            throw validationError(
                "LOCAL_DIRECTORY_PATH_OUTSIDE_ALLOWED_ROOTS",
                "storagePath must be inside a configured allowed server root directory."
            );
        }

        if (!Files.isDirectory(realPath)) {
            throw validationError(
                "LOCAL_DIRECTORY_PATH_NOT_DIRECTORY",
                "storagePath must point to a directory."
            );
        }

        if (!Files.isReadable(realPath)) {
            throw validationError(
                "LOCAL_DIRECTORY_PATH_NOT_READABLE",
                "storagePath cannot be read by the ProbeFlow server process."
            );
        }

        return realPath;
    }

    private static List<Path> realAllowedRoot(String rawRoot) {
        try {
            var root = Path.of(rawRoot);
            if (!root.isAbsolute()) {
                return List.of();
            }
            var realRoot = root.toRealPath();
            if (!Files.isDirectory(realRoot) || !Files.isReadable(realRoot)) {
                return List.of();
            }
            return List.of(realRoot);
        } catch (InvalidPathException | IOException exception) {
            return List.of();
        }
    }

    private ProjectImportValidationException validationError(String code, String message) {
        return new ProjectImportValidationException(
            ProjectImportErrorResponse.validation(code, "storagePath", message)
        );
    }

    static List<String> splitAllowedRoots(String rawAllowedRoots) {
        if (rawAllowedRoots == null || rawAllowedRoots.isBlank()) {
            return List.of();
        }
        var roots = new ArrayList<String>();
        for (var root : rawAllowedRoots.split(",")) {
            if (!root.isBlank()) {
                roots.add(root.trim());
            }
        }
        return roots;
    }
}
