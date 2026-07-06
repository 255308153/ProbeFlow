package com.probeflow.testagent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.probeflow.testagent.controlledplanner.FakeControlledPlanner;
import com.probeflow.testagent.controlledplanner.FakePlannerScenario;
import com.probeflow.testagent.replanning.ReplanningStatus;
import com.probeflow.testagent.replanning.ReplanningTrigger;
import com.probeflow.testagent.task.PlanStep;
import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.PlanStepType;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LimitedOrchestrationReplanningBoundaryTests {

    @Autowired
    private TaskInitializationService taskInitializationService;

    @Autowired
    private TaskOrchestrationApplicationService taskOrchestrationApplicationService;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private RecordingPlanStepRunner planStepRunner;

    @BeforeEach
    void clearRunner() {
        planStepRunner.clear();
    }

    @Test
    void defaultTemplateFailureDoesNotEnterReplanningLoop() {
        var taskId = initializeApiTestTask(Map.of());
        planStepRunner.failOn(PlanStepType.GENERATE_CASES, "case generation failed");

        var result = taskOrchestrationApplicationService.runInitializedTask(taskId);

        assertThat(result.finalStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.blockerDetails()).containsExactly("case generation failed");
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(taskId))
            .extracting(PlanStep::getStepType, PlanStep::getStepStatus)
            .containsExactly(
                tuple(PlanStepType.ANALYZE_CODE_API, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.RETRIEVE_KNOWLEDGE, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.GENERATE_CASES, PlanStepStatus.FAILED),
                tuple(PlanStepType.EXECUTE_BATCH, PlanStepStatus.SKIPPED),
                tuple(PlanStepType.ANALYZE_FAILURE, PlanStepStatus.SKIPPED),
                tuple(PlanStepType.GENERATE_REPORT, PlanStepStatus.SKIPPED)
            );
        assertThat(tasks.findById(taskId).orElseThrow().getMetadata())
            .doesNotContainKey("lastOrchestrationReplanning")
            .doesNotContainKey("replanningAttemptRecords");
    }

    @Test
    void explicitFailureReplanningTruncatesDownstreamAndAppendsAuditedRecoveryStep() {
        var taskId = initializeApiTestTask(replanningMetadata(FakePlannerScenario.REPLAN));
        planStepRunner.failOn(PlanStepType.GENERATE_CASES, "case generation failed");

        var result = taskOrchestrationApplicationService.runInitializedTask(taskId);

        assertThat(result.finalStatus()).isEqualTo(TaskStatus.ANALYZING_RESULTS);
        assertThat(result.blockerDetails())
            .contains("case generation failed", "Replanning APPLIED for PLAN_STEP_FAILED");
        assertThat(planStepRunner.calls()).containsExactly(
            PlanStepType.ANALYZE_CODE_API,
            PlanStepType.RETRIEVE_KNOWLEDGE,
            PlanStepType.GENERATE_CASES
        );
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(taskId))
            .extracting(PlanStep::getStepType, PlanStep::getStepStatus)
            .containsExactly(
                tuple(PlanStepType.ANALYZE_CODE_API, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.RETRIEVE_KNOWLEDGE, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.GENERATE_CASES, PlanStepStatus.FAILED),
                tuple(PlanStepType.EXECUTE_BATCH, PlanStepStatus.SKIPPED),
                tuple(PlanStepType.ANALYZE_FAILURE, PlanStepStatus.SKIPPED),
                tuple(PlanStepType.GENERATE_REPORT, PlanStepStatus.SKIPPED),
                tuple(PlanStepType.ANALYZE_FAILURE, PlanStepStatus.PENDING)
            );

        var metadata = tasks.findById(taskId).orElseThrow().getMetadata();
        assertThat(metadata).containsEntry("replanningAttemptCount", 1);
        assertThat(metadataMap(metadata.get("lastOrchestrationReplanning")))
            .containsEntry("source", "TaskOrchestrationApplicationService")
            .containsEntry("limitedIntegration", true)
            .containsEntry("trigger", ReplanningTrigger.PLAN_STEP_FAILED.name())
            .containsEntry("status", ReplanningStatus.APPLIED.name());
        assertThat(metadataMap(metadata.get("replanningAttemptRecords"))).hasSize(1);
    }

    @Test
    void explicitReadinessReplanningPausesForHumanWithoutRunningDownstream() {
        var taskId = initializeApiTestTask(replanningMetadata(FakePlannerScenario.WAIT_FOR_HUMAN));
        planStepRunner.blockOn(
            PlanStepType.EXECUTE_BATCH,
            "HTTP execution blocked by readiness checks; blockers=1",
            List.of("MISSING_ENVIRONMENT: task metadata environment is required before HTTP execution")
        );

        var result = taskOrchestrationApplicationService.runInitializedTask(taskId);

        assertThat(result.finalStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
        assertThat(result.blockerDetails())
            .contains(
                "MISSING_ENVIRONMENT: task metadata environment is required before HTTP execution",
                "Replanning WAITING_FOR_HUMAN for EXECUTION_READINESS_MISSING"
            );
        assertThat(planStepRunner.calls()).containsExactly(
            PlanStepType.ANALYZE_CODE_API,
            PlanStepType.RETRIEVE_KNOWLEDGE,
            PlanStepType.GENERATE_CASES,
            PlanStepType.EXECUTE_BATCH
        );
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(taskId))
            .extracting(PlanStep::getStepType, PlanStep::getStepStatus)
            .containsExactly(
                tuple(PlanStepType.ANALYZE_CODE_API, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.RETRIEVE_KNOWLEDGE, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.GENERATE_CASES, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.EXECUTE_BATCH, PlanStepStatus.SKIPPED),
                tuple(PlanStepType.ANALYZE_FAILURE, PlanStepStatus.PENDING),
                tuple(PlanStepType.GENERATE_REPORT, PlanStepStatus.PENDING)
            );

        var metadata = tasks.findById(taskId).orElseThrow().getMetadata();
        assertThat(metadata).containsKey("requiredHumanInput");
        assertThat(metadataMap(metadata.get("lastOrchestrationReplanning")))
            .containsEntry("trigger", ReplanningTrigger.EXECUTION_READINESS_MISSING.name())
            .containsEntry("status", ReplanningStatus.WAITING_FOR_HUMAN.name());
    }

    private String initializeApiTestTask(Map<String, Object> metadata) {
        return taskInitializationService.initialize(new TaskInitializationRequest(
            null,
            TaskType.API_TEST,
            "Explore order APIs",
            TaskSourceType.OPENAPI,
            "source-material-orders",
            PromotionMode.AUTO,
            List.of("api-create-order", "api-pay-order"),
            List.of(),
            TaskPriority.HIGH,
            "phase5-test",
            metadata
        )).taskId();
    }

    private Map<String, Object> replanningMetadata(FakePlannerScenario scenario) {
        return Map.of(
            "replanningEnabled", true,
            "replanningConstraints", List.of(Map.of(
                "code", FakeControlledPlanner.SCENARIO_CONSTRAINT_CODE,
                "description", scenario.name()
            ))
        );
    }

    private Map<String, Object> metadataMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, entryValue) -> result.put(key.toString(), entryValue));
        return Map.copyOf(result);
    }

    @TestConfiguration
    static class OrchestrationReplanningTestConfiguration {

        @Bean
        @Primary
        RecordingPlanStepRunner recordingPlanStepRunner() {
            return new RecordingPlanStepRunner();
        }
    }

    static class RecordingPlanStepRunner implements PlanStepRunner {

        private final List<PlanStepType> calls = new ArrayList<>();
        private final Map<PlanStepType, StepOutcome> outcomes = new EnumMap<>(PlanStepType.class);

        @Override
        public StepOutcome run(com.probeflow.testagent.task.Task task, PlanStep step) {
            calls.add(step.getStepType());
            return outcomes.getOrDefault(step.getStepType(), StepOutcome.succeeded());
        }

        void failOn(PlanStepType stepType, String blocker) {
            outcomes.put(stepType, StepOutcome.failed(blocker));
        }

        void blockOn(PlanStepType stepType, String summary, List<String> blockers) {
            outcomes.put(stepType, StepOutcome.blocked(summary, blockers));
        }

        void clear() {
            calls.clear();
            outcomes.clear();
        }

        List<PlanStepType> calls() {
            return List.copyOf(calls);
        }
    }
}
