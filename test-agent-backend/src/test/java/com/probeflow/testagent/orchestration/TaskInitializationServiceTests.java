package com.probeflow.testagent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.PlanStepType;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaskInitializationServiceTests {

    @Autowired
    private TaskInitializationService taskInitializationService;

    @Autowired
    private TaskTemplateRegistry taskTemplateRegistry;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private EntityManager entityManager;

    @Test
    void initializesAutomaticApiTestTaskWithDeterministicPlanSteps() {
        var result = taskInitializationService.initialize(new TaskInitializationRequest(
            null,
            TaskType.API_TEST,
            "Explore order APIs",
            TaskSourceType.OPENAPI,
            "source-material-orders",
            PromotionMode.AUTO,
            List.of("api-create-order", "api-pay-order"),
            List.of(),
            TaskPriority.HIGH,
            "phase9-test",
            Map.of("environment", "staging")
        ));

        entityManager.flush();
        entityManager.clear();

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getTaskType()).isEqualTo(TaskType.API_TEST);
        assertThat(task.getTaskName()).isEqualTo("Explore order APIs");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getSourceType()).isEqualTo(TaskSourceType.OPENAPI);
        assertThat(task.getSourceRef()).isEqualTo("source-material-orders");
        assertThat(task.getTargetApiSpecIds()).containsExactly("api-create-order", "api-pay-order");
        assertThat(task.getPromotionMode()).isEqualTo(PromotionMode.AUTO);
        assertThat(task.getPriority()).isEqualTo(TaskPriority.HIGH);
        assertThat(task.getCreator()).isEqualTo("phase9-test");
        assertThat(task.getMetadata())
            .containsEntry("phase", "9")
            .containsEntry("templateName", "API_TEST_AUTO_V1")
            .containsEntry("initializedBy", "TaskInitializationService")
            .containsEntry("environment", "staging");

        var persistedSteps = planSteps.findByTaskIdOrderByStepOrderAsc(result.taskId());
        assertThat(persistedSteps).extracting(step -> step.getStepOrder())
            .containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(persistedSteps).extracting(step -> step.getStepType())
            .containsExactly(
                PlanStepType.ANALYZE_CODE_API,
                PlanStepType.RETRIEVE_KNOWLEDGE,
                PlanStepType.GENERATE_CASES,
                PlanStepType.EXECUTE_BATCH,
                PlanStepType.ANALYZE_FAILURE,
                PlanStepType.GENERATE_REPORT
            );
        assertThat(persistedSteps).allSatisfy(step -> {
            assertThat(step.getStepStatus()).isEqualTo(PlanStepStatus.PENDING);
            assertThat(step.getGoal()).isNotBlank();
            assertThat(step.getInputRef()).isEqualTo(result.taskId());
            assertThat(step.getRetryCount()).isZero();
        });
        assertThat(result.planStepTypes()).containsExactlyElementsOf(persistedSteps.stream()
            .map(step -> step.getStepType())
            .toList());
        assertThat(result.created()).isTrue();
    }

    @Test
    void initializesRegressionTaskWithTemplateThatSkipsAnalysisAndGeneration() {
        var result = taskInitializationService.initialize(new TaskInitializationRequest(
            null,
            TaskType.REGRESSION,
            "Run order regression",
            TaskSourceType.MANUAL,
            "regression-suite-order",
            PromotionMode.AUTO,
            List.of("api-create-order"),
            List.of("case-happy-path", "case-timeout"),
            TaskPriority.MEDIUM,
            "phase9-test",
            Map.of("trigger", "nightly")
        ));

        var task = tasks.findById(result.taskId()).orElseThrow();
        assertThat(task.getTaskType()).isEqualTo(TaskType.REGRESSION);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getSourceType()).isEqualTo(TaskSourceType.MANUAL);
        assertThat(task.getTargetApiSpecIds()).containsExactly("api-create-order");
        assertThat(task.getMetadata())
            .containsEntry("templateName", "REGRESSION_AUTO_V1")
            .containsEntry("trigger", "nightly");
        assertThat(task.getMetadata().get("selectedCaseIds"))
            .isEqualTo(List.of("case-happy-path", "case-timeout"));

        var persistedSteps = planSteps.findByTaskIdOrderByStepOrderAsc(result.taskId());
        assertThat(persistedSteps).extracting(step -> step.getStepType())
            .containsExactly(
                PlanStepType.EXECUTE_BATCH,
                PlanStepType.ANALYZE_FAILURE,
                PlanStepType.GENERATE_REPORT
            );
        assertThat(persistedSteps).extracting(step -> step.getStepType())
            .doesNotContain(PlanStepType.ANALYZE_CODE_API, PlanStepType.GENERATE_CASES);
        assertThat(result.planStepTypes()).containsExactly(
            PlanStepType.EXECUTE_BATCH,
            PlanStepType.ANALYZE_FAILURE,
            PlanStepType.GENERATE_REPORT
        );
    }

    @Test
    void templateRegistryReturnsDistinctOrderedTemplatesForApiTestAndRegressionModes() {
        var apiTestAuto = taskTemplateRegistry.templateFor(TaskType.API_TEST, TaskSourceType.CODE_REPO, PromotionMode.AUTO);
        var apiTestManual = taskTemplateRegistry.templateFor(TaskType.API_TEST, TaskSourceType.CODE_REPO, PromotionMode.MANUAL);
        var regression = taskTemplateRegistry.templateFor(TaskType.REGRESSION, TaskSourceType.MANUAL, PromotionMode.AUTO);

        assertThat(apiTestAuto.templateName()).isEqualTo("API_TEST_AUTO_V1");
        assertThat(apiTestManual.templateName()).isEqualTo("API_TEST_MANUAL_V1");
        assertThat(regression.templateName()).isEqualTo("REGRESSION_AUTO_V1");
        assertThat(apiTestAuto.steps()).extracting(TaskPlanStepTemplate::stepType)
            .containsExactly(
                PlanStepType.ANALYZE_CODE_API,
                PlanStepType.RETRIEVE_KNOWLEDGE,
                PlanStepType.GENERATE_CASES,
                PlanStepType.EXECUTE_BATCH,
                PlanStepType.ANALYZE_FAILURE,
                PlanStepType.GENERATE_REPORT
            );
        assertThat(apiTestManual.steps()).extracting(TaskPlanStepTemplate::stepType)
            .containsExactlyElementsOf(apiTestAuto.steps().stream().map(TaskPlanStepTemplate::stepType).toList());
        assertThat(regression.steps()).extracting(TaskPlanStepTemplate::stepType)
            .containsExactly(
                PlanStepType.EXECUTE_BATCH,
                PlanStepType.ANALYZE_FAILURE,
                PlanStepType.GENERATE_REPORT
            );
    }

    @Test
    void preparingExistingTaskDoesNotDuplicatePlanSteps() {
        var first = taskInitializationService.initialize(new TaskInitializationRequest(
            null,
            TaskType.API_TEST,
            "Explore order APIs",
            TaskSourceType.OPENAPI,
            "source-material-orders",
            PromotionMode.MANUAL,
            List.of("api-create-order"),
            List.of(),
            TaskPriority.MEDIUM,
            "phase9-test",
            Map.of()
        ));

        var second = taskInitializationService.initialize(new TaskInitializationRequest(
            first.taskId(),
            TaskType.API_TEST,
            "Ignored on prepare",
            TaskSourceType.OPENAPI,
            "ignored-source",
            PromotionMode.MANUAL,
            List.of("ignored-api"),
            List.of(),
            TaskPriority.LOW,
            "phase9-test",
            Map.of()
        ));

        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(second.created()).isFalse();
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(first.taskId())).hasSize(6);
        assertThat(tasks.findById(first.taskId()).orElseThrow().getSourceRef()).isEqualTo("source-material-orders");
    }
}
