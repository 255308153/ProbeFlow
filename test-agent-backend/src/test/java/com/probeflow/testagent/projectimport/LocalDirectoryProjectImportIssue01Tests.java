package com.probeflow.testagent.projectimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.sourcematerial.SourceMaterialRepository;
import com.probeflow.testagent.task.TaskRepository;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LocalDirectoryProjectImportIssue01Tests {

    private static final Path ALLOWED_ROOT = createTempRoot("probeflow-project-import-allowed-");
    private static final Path OUTSIDE_ROOT = createTempRoot("probeflow-project-import-outside-");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SourceMaterialRepository sourceMaterials;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @DynamicPropertySource
    static void projectImportProperties(DynamicPropertyRegistry registry) {
        registry.add("probeflow.project-import.local-directory.allowed-roots", ALLOWED_ROOT::toString);
    }

    @Test
    void safeAbsoluteDirectoryReturnsLocalDirectoryImportContractWithoutCreatingAnalysisArtifacts() throws Exception {
        var projectDir = Files.createDirectories(ALLOWED_ROOT.resolve("orders-service"));
        var before = artifactCounts();

        mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(localDirectoryBody(projectDir.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.materialId").value(nullValue()))
            .andExpect(jsonPath("$.taskId").value(nullValue()))
            .andExpect(jsonPath("$.status").value("VALIDATED"))
            .andExpect(jsonPath("$.materialType").value("SOURCE_DIRECTORY"))
            .andExpect(jsonPath("$.apiSpecCount").value(0))
            .andExpect(jsonPath("$.warnings").isArray())
            .andExpect(jsonPath("$.warnings").isEmpty())
            .andExpect(jsonPath("$.blockers").isArray())
            .andExpect(jsonPath("$.blockers").isEmpty());

        assertThat(artifactCounts()).isEqualTo(before);
    }

    @Test
    void emptyPathReturnsStructuredFieldValidationErrorWithoutCreatingArtifacts() throws Exception {
        assertStoragePathError("", "LOCAL_DIRECTORY_PATH_REQUIRED");
    }

    @Test
    void relativePathIsRejectedWithStableErrorCodeWithoutCreatingArtifacts() throws Exception {
        assertStoragePathError("orders-service", "LOCAL_DIRECTORY_PATH_NOT_ABSOLUTE");
    }

    @Test
    void missingPathIsRejectedWithStableErrorCodeWithoutCreatingArtifacts() throws Exception {
        assertStoragePathError(ALLOWED_ROOT.resolve("missing-service").toString(), "LOCAL_DIRECTORY_PATH_NOT_FOUND");
    }

    @Test
    void regularFilePathIsRejectedWithStableErrorCodeWithoutCreatingArtifacts() throws Exception {
        var file = ALLOWED_ROOT.resolve("orders.txt");
        Files.writeString(file, "not a source directory");

        assertStoragePathError(file.toString(), "LOCAL_DIRECTORY_PATH_NOT_DIRECTORY");
    }

    @Test
    void unreadableDirectoryIsRejectedWithStableErrorCodeWithoutCreatingArtifacts() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        var unreadable = Files.createDirectories(ALLOWED_ROOT.resolve("unreadable-service"));
        var readablePermissions = PosixFilePermissions.fromString("rwx------");
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(unreadable);

        try {
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("--x------"));
            assumeTrue(!Files.isReadable(unreadable));
            assertStoragePathError(unreadable.toString(), "LOCAL_DIRECTORY_PATH_NOT_READABLE");
        } finally {
            Files.setPosixFilePermissions(unreadable, originalPermissions.isEmpty() ? readablePermissions : originalPermissions);
        }
    }

    @Test
    void pathOutsideAllowedRootIsRejectedWithStableErrorCodeWithoutCreatingArtifacts() throws Exception {
        var outsideProject = Files.createDirectories(OUTSIDE_ROOT.resolve("orders-service"));

        assertStoragePathError(outsideProject.toString(), "LOCAL_DIRECTORY_PATH_OUTSIDE_ALLOWED_ROOTS");
    }

    @Test
    void dotDotCannotEscapeAllowedRootAfterRealPathNormalization() throws Exception {
        var outsideProject = Files.createDirectories(OUTSIDE_ROOT.resolve("dotdot-service"));
        var escapingPath = ALLOWED_ROOT
            .resolve("..")
            .resolve(OUTSIDE_ROOT.getFileName())
            .resolve(outsideProject.getFileName());

        assertStoragePathError(escapingPath.toString(), "LOCAL_DIRECTORY_PATH_OUTSIDE_ALLOWED_ROOTS");
    }

    @Test
    void symlinkCannotEscapeAllowedRootAfterRealPathNormalization() throws Exception {
        var outsideProject = Files.createDirectories(OUTSIDE_ROOT.resolve("symlink-service"));
        var link = ALLOWED_ROOT.resolve("linked-service");
        try {
            Files.createSymbolicLink(link, outsideProject);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are not available in this test environment.");
        }

        assertStoragePathError(link.toString(), "LOCAL_DIRECTORY_PATH_OUTSIDE_ALLOWED_ROOTS");
    }

    @Test
    void errorResponseDoesNotEchoSensitiveLocalPathDetails() throws Exception {
        var secretLookingPath = OUTSIDE_ROOT.resolve("sk-live-secret-service");
        Files.createDirectories(secretLookingPath);
        var before = artifactCounts();

        var response = mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(localDirectoryBody(secretLookingPath.toString()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("LOCAL_DIRECTORY_PATH_OUTSIDE_ALLOWED_ROOTS"))
            .andExpect(jsonPath("$.category").value("VALIDATION"))
            .andExpect(jsonPath("$.field").value("storagePath"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(response)
            .doesNotContain(secretLookingPath.toString())
            .doesNotContain("sk-live")
            .doesNotContain("PROBEFLOW_");
        assertThat(artifactCounts()).isEqualTo(before);
    }

    @Test
    void localDirectoryImportIsDisabledWhenNoAllowedRootsAreConfigured() throws Exception {
        var service = new LocalDirectoryProjectImportApplicationService(
            new LocalDirectoryPathValidator(LocalDirectoryPathValidator.splitAllowedRoots(""))
        );
        var projectDir = Files.createDirectories(ALLOWED_ROOT.resolve("disabled-service"));

        try {
            service.importLocalDirectory(new ProjectImportLocalDirectoryRequest(
                "disabled-service",
                projectDir.toString(),
                "tester"
            ));
        } catch (ProjectImportValidationException exception) {
            assertThat(exception.error().code()).isEqualTo("LOCAL_DIRECTORY_ALLOWED_ROOTS_REQUIRED");
            assertThat(exception.error().field()).isEqualTo("storagePath");
            return;
        }

        throw new AssertionError("Expected local directory import to require configured allowed roots.");
    }

    private void assertStoragePathError(String storagePath, String expectedCode) throws Exception {
        var before = artifactCounts();
        var response = mockMvc.perform(post("/api/project-imports/local-directory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(localDirectoryBody(storagePath))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(expectedCode))
            .andExpect(jsonPath("$.category").value("VALIDATION"))
            .andExpect(jsonPath("$.field").value("storagePath"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(response).doesNotContain("class ").doesNotContain("Exception");
        assertThat(artifactCounts()).isEqualTo(before);
    }

    private Map<String, Object> localDirectoryBody(String storagePath) {
        return Map.of(
            "projectName", "orders-service",
            "storagePath", storagePath,
            "requestedBy", "tester"
        );
    }

    private ArtifactCounts artifactCounts() {
        return new ArtifactCounts(sourceMaterials.count(), tasks.count(), apiSpecs.count());
    }

    private static Path createTempRoot(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create project import test root", exception);
        }
    }

    private record ArtifactCounts(long sourceMaterialCount, long taskCount, long apiSpecCount) {
    }
}
