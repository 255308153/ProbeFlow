package com.probeflow.testagent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.analysis.ApiAnalysisApplicationService;
import com.probeflow.testagent.failureanalysis.FailureAnalysisApplicationService;
import com.probeflow.testagent.failureanalysis.FailureAnalysisMode;
import com.probeflow.testagent.failureanalysis.GroupedFailureSummary;
import com.probeflow.testagent.failureanalysis.TaskFailureAnalysisCounts;
import com.probeflow.testagent.failureanalysis.TaskFailureAnalysisRequest;
import com.probeflow.testagent.failureanalysis.TaskFailureAnalysisResult;
import com.probeflow.testagent.httpexecution.HttpExecutionApplicationService;
import com.probeflow.testagent.httpexecution.HttpExecutionCaseResult;
import com.probeflow.testagent.httpexecution.HttpExecutionCounts;
import com.probeflow.testagent.httpexecution.HttpExecutionOutcomeStatus;
import com.probeflow.testagent.httpexecution.HttpExecutionRequest;
import com.probeflow.testagent.httpexecution.HttpExecutionResult;
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
import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionRepository;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionStatus;
import com.probeflow.testagent.testcase.CaseCategory;
import com.probeflow.testagent.testcase.CasePriority;
import com.probeflow.testagent.testcase.CaseRiskLevel;
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcase.CaseStatus;
import com.probeflow.testagent.testcase.DetailType;
import com.probeflow.testagent.testcase.StaleStatus;
import com.probeflow.testagent.testcase.TestCase;
import com.probeflow.testagent.testcase.TestCaseMode;
import com.probeflow.testagent.testcase.TestCaseRepository;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationApplicationService;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RegressionWorkflowOrchestrationTests {

    @Autowired
    private TaskInitializationService taskInitializationService;

    @Autowired
    private TaskOrchestrationApplicationService taskOrchestrationApplicationService;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private TestCaseRepository testCases;

    @Autowired
    private TaskCaseExecutionRepository taskCaseExecutions;

    @Autowired
    private EntityManager entityManager;

    @MockBean
    private ApiAnalysisApplicationService apiAnalysis;

    @MockBean
    private TestCaseGenerationApplicationService testCaseGeneration;

    @MockBean
    private HttpExecutionApplicationService httpExecution;

    @MockBean
    private FailureAnalysisApplicationService failureAnalysis;

    @MockBean
    private ReportGenerationApplicationService reportGeneration;

    @Test
    void regressionInitializesExistingCasesExecutesAnalyzesFailuresAndReportsWithoutGeneration() {
        testCases.save(newTestCase("case-fresh", "api-create-order", StaleStatus.FRESH));
        testCases.save(newTestCase("case-stale", "api-pay-order", StaleStatus.STALE));
        when(httpExecution.execute(any()))
            .thenReturn(new HttpExecutionResult(
                "task-regression",
                "staging",
                ExecutionMode.BATCH,
                List.of(
                    new HttpExecutionCaseResult("case-fresh", "exec-pass", HttpExecutionOutcomeStatus.PASSED, 12L, 200, "ok"),
                    new HttpExecutionCaseResult("case-stale", "exec-fail", HttpExecutionOutcomeStatus.FAILED, 30L, 500, "failed")
                ),
                new HttpExecutionCounts(2, 1, 1, 0, 0, 0)
            ));
        when(failureAnalysis.analyzeTask(any()))
            .thenReturn(new TaskFailureAnalysisResult(
                "task-regression",
                FailureAnalysisMode.BASIC,
                new TaskFailureAnalysisCounts(2, 0, 1, Map.of(), Map.of(), Map.of(), Map.of()),
                List.of(),
                List.<GroupedFailureSummary>of()
            ));
        when(reportGeneration.generateTaskReport(any()))
            .thenReturn(new ReportGenerationResult("report-regression", "task-regression", "COMPLETE", 2, 2));

        var initialized = taskInitializationService.initialize(new TaskInitializationRequest(
            null,
            TaskType.REGRESSION,
            "Run order regression",
            TaskSourceType.MANUAL,
            "regression-suite-order",
            PromotionMode.AUTO,
            List.of("api-create-order", "api-pay-order"),
            List.of("case-fresh", "case-stale"),
            TaskPriority.MEDIUM,
            "phase9-test",
            Map.of(
                "environment", "staging",
                "dryRun", true,
                "environmentVariables", Map.of("baseUrl", "https://api.example.test")
            )
        ));
        entityManager.flush();
        entityManager.clear();

        var preparedExecutions = taskCaseExecutions.findAllByTaskIdOrderByCaseIdAscIdAsc(initialized.taskId());
        assertThat(preparedExecutions)
            .extracting(execution -> execution.getCaseId(), execution -> execution.getExecutionStatus())
            .containsExactly(
                tuple("case-fresh", TaskCaseExecutionStatus.PENDING),
                tuple("case-stale", TaskCaseExecutionStatus.PENDING)
            );
        assertThat(preparedExecutions.get(1).getSnapshotJson())
            .containsEntry("staleStatus", "STALE")
            .containsEntry("primaryApiSpecId", "api-pay-order");
        assertThat(tasks.findById(initialized.taskId()).orElseThrow().getMetadata())
            .containsEntry("selectedCaseIds", List.of("case-fresh", "case-stale"))
            .containsEntry("staleCaseIds", List.of("case-stale"));

        var result = taskOrchestrationApplicationService.runInitializedTask(initialized.taskId());

        assertThat(result.finalStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.reportId()).isEqualTo("report-regression");
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(initialized.taskId()))
            .extracting(step -> step.getStepType(), step -> step.getStepStatus())
            .containsExactly(
                tuple(PlanStepType.EXECUTE_BATCH, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.ANALYZE_FAILURE, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.GENERATE_REPORT, PlanStepStatus.SUCCESS)
            );
        var httpRequest = ArgumentCaptor.forClass(HttpExecutionRequest.class);
        verify(httpExecution).execute(httpRequest.capture());
        assertThat(httpRequest.getValue().selectedCaseIds()).containsExactly("case-fresh", "case-stale");
        verify(failureAnalysis).analyzeTask(TaskFailureAnalysisRequest.basic(initialized.taskId(), List.of("exec-pass", "exec-fail")));
        verify(reportGeneration).generateTaskReport(ReportGenerationRequest.forTask(initialized.taskId()));
        verifyNoInteractions(apiAnalysis, testCaseGeneration);
    }

    private TestCase newTestCase(String caseId, String apiSpecId, StaleStatus staleStatus) {
        var testCase = new TestCase();
        testCase.setCaseId(caseId);
        testCase.setPrimaryApiSpecId(apiSpecId);
        testCase.setCaseCategory(CaseCategory.API);
        testCase.setMode(TestCaseMode.SINGLE);
        testCase.setTitle("Regression case " + caseId);
        testCase.setDescription("Regression case");
        testCase.setPreconditions(List.of());
        testCase.setExpectedResult("Request succeeds");
        testCase.setPriority(CasePriority.MEDIUM);
        testCase.setRiskLevel(CaseRiskLevel.MEDIUM);
        testCase.setTags(List.of("regression"));
        testCase.setStatus(CaseStatus.READY);
        testCase.setSource(CaseSource.MANUAL);
        testCase.setDetailType(DetailType.API);
        testCase.setDetail(Map.of("method", "GET", "path", "/orders"));
        testCase.setSteps(List.of());
        testCase.setStaleStatus(staleStatus);
        testCase.setBasedOnApiSpecVersions(Map.of(apiSpecId, 1));
        testCase.setGeneratedFromSingleCaseIds(List.of());
        return testCase;
    }
}
