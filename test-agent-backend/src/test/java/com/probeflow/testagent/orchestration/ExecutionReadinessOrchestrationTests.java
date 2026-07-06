package com.probeflow.testagent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.analysis.ApiAnalysisApplicationService;
import com.probeflow.testagent.analysis.ApiAnalysisResult;
import com.probeflow.testagent.failureanalysis.FailureAnalysisApplicationService;
import com.probeflow.testagent.httpexecution.HttpExecutionApplicationService;
import com.probeflow.testagent.knowledge.KnowledgeContext;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalApplicationService;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalResult;
import com.probeflow.testagent.report.ReportGenerationApplicationService;
import com.probeflow.testagent.report.ReportGenerationRequest;
import com.probeflow.testagent.report.ReportGenerationResult;
import com.probeflow.testagent.task.PlanStepRepository;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.PlanStepType;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationApplicationService;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationCounts;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationMode;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationResult;
import com.probeflow.testagent.testcasegeneration.TestCasePromotionResult;
import com.probeflow.testagent.testcasegeneration.TestCasePromotionService;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExecutionReadinessOrchestrationTests {

    @Autowired
    private TaskInitializationService taskInitializationService;

    @Autowired
    private TaskOrchestrationApplicationService taskOrchestrationApplicationService;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @MockBean
    private ApiAnalysisApplicationService apiAnalysis;

    @MockBean
    private KnowledgeRetrievalApplicationService knowledgeRetrieval;

    @MockBean
    private TestCaseGenerationApplicationService testCaseGeneration;

    @MockBean
    private TestCasePromotionService testCasePromotion;

    @MockBean
    private HttpExecutionApplicationService httpExecution;

    @MockBean
    private FailureAnalysisApplicationService failureAnalysis;

    @MockBean
    private ReportGenerationApplicationService reportGeneration;

    @Test
    void missingExecutionReadinessInputsSkipHttpExecutionAndGeneratePartialReport() {
        when(apiAnalysis.analyze(any()))
            .thenReturn(new ApiAnalysisResult("material-1", "analysis-task-1", true, "OPENAPI", null, null, List.of("api-1")));
        when(knowledgeRetrieval.retrieveForApiSpec(any(), any()))
            .thenReturn(new KnowledgeRetrievalResult("query", List.of(), KnowledgeContext.empty(true, true), 0.0d, 0, 0, true));
        when(testCaseGeneration.generate(any()))
            .thenReturn(new TestCaseGenerationResult(
                "task-readiness",
                "session-1",
                TestCaseGenerationMode.SINGLE,
                List.of("api-1"),
                List.of("draft-1"),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                new TestCaseGenerationCounts(1, 0, 0, 0)
            ));
        when(testCasePromotion.promote(any()))
            .thenReturn(new TestCasePromotionResult(List.of("case-1"), List.of(), Map.of()));
        when(reportGeneration.generateTaskReport(any()))
            .thenReturn(new ReportGenerationResult("report-partial", "task-readiness", "PARTIAL", 1, 0));
        var taskId = taskInitializationService.initialize(new TaskInitializationRequest(
            null,
            TaskType.API_TEST,
            "Explore order APIs",
            TaskSourceType.OPENAPI,
            "material-1",
            PromotionMode.AUTO,
            List.of("api-1"),
            List.of(),
            TaskPriority.HIGH,
            "phase9-test",
            Map.of(
                "sessionId", "session-1",
                "authRequired", true
            )
        )).taskId();

        var result = taskOrchestrationApplicationService.runInitializedTask(taskId);

        assertThat(result.finalStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.reportId()).isEqualTo("report-partial");
        assertThat(result.blockerDetails())
            .containsExactly(
                "MISSING_ENVIRONMENT: task metadata environment is required before HTTP execution",
                "MISSING_BASE_URL: task metadata environmentVariables.baseUrl is required before HTTP execution",
                "MISSING_AUTH: task metadata authVariables must contain credentials when authRequired=true"
            );
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(taskId))
            .extracting(step -> step.getStepType(), step -> step.getStepStatus())
            .containsExactly(
                tuple(PlanStepType.ANALYZE_CODE_API, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.RETRIEVE_KNOWLEDGE, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.GENERATE_CASES, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.EXECUTE_BATCH, PlanStepStatus.SKIPPED),
                tuple(PlanStepType.ANALYZE_FAILURE, PlanStepStatus.SKIPPED),
                tuple(PlanStepType.GENERATE_REPORT, PlanStepStatus.SUCCESS)
            );
        var metadata = tasks.findById(taskId).orElseThrow().getMetadata();
        assertThat(metadata)
            .containsEntry("executionReadinessStatus", "BLOCKED")
            .containsEntry("needsFailureAnalysis", false);
        assertThat(metadata.get("executionReadinessBlockers")).asList()
            .contains(
                Map.of(
                    "code", "MISSING_ENVIRONMENT",
                    "field", "environment",
                    "message", "task metadata environment is required before HTTP execution"
                ),
                Map.of(
                    "code", "MISSING_BASE_URL",
                    "field", "environmentVariables.baseUrl",
                    "message", "task metadata environmentVariables.baseUrl is required before HTTP execution"
                ),
                Map.of(
                    "code", "MISSING_AUTH",
                    "field", "authVariables",
                    "message", "task metadata authVariables must contain credentials when authRequired=true"
                )
            );
        verifyNoInteractions(httpExecution, failureAnalysis);
        verify(reportGeneration).generateTaskReport(ReportGenerationRequest.forTask(taskId));
    }
}
