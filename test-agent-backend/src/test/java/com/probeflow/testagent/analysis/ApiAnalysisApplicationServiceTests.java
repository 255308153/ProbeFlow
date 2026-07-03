package com.probeflow.testagent.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.sourcematerial.IngestStatus;
import com.probeflow.testagent.sourcematerial.MaterialType;
import com.probeflow.testagent.sourcematerial.SourceMaterialRepository;
import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApiAnalysisApplicationServiceTests {

    @Autowired
    private ApiAnalysisApplicationService apiAnalysis;

    @Autowired
    private SourceMaterialRepository sourceMaterials;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @TempDir
    private Path tempDir;

    @Test
    void readableOpenApiMaterialCreatesAnalysisTaskAndRoutesParser() throws Exception {
        var openApiFile = tempDir.resolve("orders.yaml");
        Files.writeString(openApiFile, "openapi: 3.0.3\ninfo:\n  title: Orders\n  version: 1.0.0\npaths: {}\n");

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.OPENAPI_FILE,
            "orders.yaml",
            openApiFile.toString(),
            openApiFile.toString(),
            "tester"
        ));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.parserRoute()).isEqualTo("openapi");
        assertThat(result.apiSpecIds()).isEmpty();

        var material = sourceMaterials.findById(result.materialId()).orElseThrow();
        assertThat(material.getIngestStatus()).isEqualTo(IngestStatus.READY);
        assertThat(material.getTaskId()).isEqualTo(result.taskId());

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getMetadata()).containsEntry("parserRoute", "openapi");

        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(result.taskId()))
            .extracting(step -> step.getStepStatus())
            .containsExactly(PlanStepStatus.SUCCESS, PlanStepStatus.SUCCESS);
        assertThat(apiSpecs.count()).isZero();
    }

    @Test
    void invalidMaterialPathFailsWithoutCreatingApiSpecs() {
        var missingFile = tempDir.resolve("missing.yaml");

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.OPENAPI_FILE,
            "missing.yaml",
            missingFile.toString(),
            missingFile.toString(),
            "tester"
        ));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MATERIAL_NOT_READABLE");

        var material = sourceMaterials.findById(result.materialId()).orElseThrow();
        assertThat(material.getIngestStatus()).isEqualTo(IngestStatus.FAILED);

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getMetadata()).containsEntry("errorCode", "MATERIAL_NOT_READABLE");

        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(result.taskId()))
            .extracting(step -> step.getStepStatus())
            .containsExactly(PlanStepStatus.FAILED, PlanStepStatus.SKIPPED);
        assertThat(apiSpecs.count()).isZero();
    }

    @Test
    void unsupportedMaterialTypeFailsWithClearRoutingState() throws Exception {
        var requirementFile = tempDir.resolve("requirements.md");
        Files.writeString(requirementFile, "# API notes\n");

        var result = apiAnalysis.analyze(ApiAnalysisRequest.createMaterial(
            MaterialType.REQUIREMENT_DOC,
            "requirements.md",
            requirementFile.toString(),
            requirementFile.toString(),
            "tester"
        ));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorCode()).isEqualTo("UNSUPPORTED_MATERIAL_TYPE");

        var material = sourceMaterials.findById(result.materialId()).orElseThrow();
        assertThat(material.getIngestStatus()).isEqualTo(IngestStatus.FAILED);

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getMetadata()).containsEntry("errorCode", "UNSUPPORTED_MATERIAL_TYPE");

        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(result.taskId()))
            .extracting(step -> step.getStepStatus())
            .containsExactly(PlanStepStatus.SUCCESS, PlanStepStatus.FAILED);
        assertThat(apiSpecs.count()).isZero();
    }
}
