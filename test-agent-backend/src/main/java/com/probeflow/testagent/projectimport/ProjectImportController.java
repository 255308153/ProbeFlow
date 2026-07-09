package com.probeflow.testagent.projectimport;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/project-imports")
public class ProjectImportController {

    private final LocalDirectoryProjectImportApplicationService localDirectoryImports;

    public ProjectImportController(LocalDirectoryProjectImportApplicationService localDirectoryImports) {
        this.localDirectoryImports = localDirectoryImports;
    }

    @PostMapping("/local-directory")
    public ResponseEntity<?> importLocalDirectory(
        @RequestBody(required = false) ProjectImportLocalDirectoryRequest request
    ) {
        try {
            return ResponseEntity.ok(localDirectoryImports.importLocalDirectory(request));
        } catch (ProjectImportValidationException exception) {
            return ResponseEntity.badRequest().body(exception.error());
        }
    }

    @GetMapping("/{materialId}")
    public ResponseEntity<?> getImportDetail(@PathVariable String materialId) {
        try {
            return ResponseEntity.ok(localDirectoryImports.getImportDetail(materialId));
        } catch (ProjectImportNotFoundException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.error());
        } catch (ProjectImportValidationException exception) {
            return ResponseEntity.badRequest().body(exception.error());
        }
    }

    @GetMapping("/{materialId}/api-specs")
    public ResponseEntity<?> listApiSpecs(@PathVariable String materialId) {
        try {
            return ResponseEntity.ok(localDirectoryImports.listApiSpecs(materialId));
        } catch (ProjectImportNotFoundException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.error());
        } catch (ProjectImportValidationException exception) {
            return ResponseEntity.badRequest().body(exception.error());
        }
    }
}
