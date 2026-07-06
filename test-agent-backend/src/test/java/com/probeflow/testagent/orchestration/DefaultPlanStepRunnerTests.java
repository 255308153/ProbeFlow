package com.probeflow.testagent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.analysis.ApiAnalysisApplicationService;
import com.probeflow.testagent.analysis.ApiAnalysisRequest;
import com.probeflow.testagent.analysis.ApiAnalysisResult;
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
import com.probeflow.testagent.knowledge.KnowledgeContext;
import com.probeflow.testagent.knowledge.KnowledgeQuery;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalApplicationService;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalResult;
import com.probeflow.testagent.observation.ObservationRepository;
import com.probeflow.testagent.report.ReportGenerationApplicationService;
import com.probeflow.testagent.report.ReportGenerationRequest;
import com.probeflow.testagent.report.ReportGenerationResult;
import com.probeflow.testagent.task.PlanStep;
import com.probeflow.testagent.task.PlanStepStatus;
import com.probeflow.testagent.task.PlanStepType;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationApplicationService;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationCounts;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationMode;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationRequest;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationResult;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultPlanStepRunnerTests {

    @Mock
    private ApiAnalysisApplicationService apiAnalysis;

    @Mock
    private KnowledgeRetrievalApplicationService knowledgeRetrieval;

    @Mock
    private TestCaseGenerationApplicationService testCaseGeneration;

    @Mock
    private HttpExecutionApplicationService httpExecution;

    @Mock
    private FailureAnalysisApplicationService failureAnalysis;

    @Mock
    private ReportGenerationApplicationService reportGeneration;

    private DefaultPlanStepRunner runner;

    @BeforeEach
    void createRunner() {
        runner = new DefaultPlanStepRunner(
            apiAnalysis,
            knowledgeRetrieval,
            testCaseGeneration,
            httpExecution,
            failureAnalysis,
            reportGeneration
        );
    }

    @Test
    void routesApiAnalysisStepThroughApiAnalysisApplicationService() {
        when(apiAnalysis.analyze(any()))
            .thenReturn(new ApiAnalysisResult("material-1", "analysis-task-1", true, "OPENAPI", null, null, List.of("api-1", "api-2")));

        var outcome = runner.run(task(), step(PlanStepType.ANALYZE_CODE_API));

        verify(apiAnalysis).analyze(ApiAnalysisRequest.existingMaterial("material-1", "phase9-test"));
        verifyNoInteractions(knowledgeRetrieval, testCaseGeneration, httpExecution, failureAnalysis, reportGeneration);
        assertThat(outcome.stepStatus()).isEqualTo(PlanStepStatus.SUCCESS);
        assertThat(outcome.summary()).isEqualTo("API analysis succeeded; apiSpecs=2");
        assertThat(outcome.resultRefs()).containsExactly("api-1", "api-2");
    }

    @Test
    void routesKnowledgeStepThroughKnowledgeRetrievalForEveryTargetApi() {
        when(knowledgeRetrieval.retrieveForApiSpec(any(), any()))
            .thenReturn(new KnowledgeRetrievalResult("query", List.of(), KnowledgeContext.empty(true, true), 0.0d, 0, 0, true));

        var outcome = runner.run(task(), step(PlanStepType.RETRIEVE_KNOWLEDGE));

        var queryCaptor = ArgumentCaptor.forClass(KnowledgeQuery.class);
        verify(knowledgeRetrieval).retrieveForApiSpec(eq("api-1"), queryCaptor.capture());
        verify(knowledgeRetrieval).retrieveForApiSpec(eq("api-2"), any(KnowledgeQuery.class));
        verifyNoInteractions(apiAnalysis, testCaseGeneration, httpExecution, failureAnalysis, reportGeneration);
        assertThat(queryCaptor.getValue().rawQuery()).contains("Explore order APIs");
        assertThat(outcome.stepStatus()).isEqualTo(PlanStepStatus.SUCCESS);
        assertThat(outcome.summary()).isEqualTo("Knowledge retrieval completed; apiTargets=2 hits=0 lowConfidence=true");
    }

    @Test
    void routesCaseGenerationThroughGenerationServiceWithBatchMode() {
        when(testCaseGeneration.generate(any()))
            .thenReturn(new TestCaseGenerationResult(
                "task-1",
                "session-1",
                TestCaseGenerationMode.BATCH,
                List.of("api-1", "api-2"),
                List.of("draft-1"),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                new TestCaseGenerationCounts(1, 0, 0, 0)
            ));

        var outcome = runner.run(task(), step(PlanStepType.GENERATE_CASES));

        var requestCaptor = ArgumentCaptor.forClass(TestCaseGenerationRequest.class);
        verify(testCaseGeneration).generate(requestCaptor.capture());
        verifyNoInteractions(apiAnalysis, knowledgeRetrieval, httpExecution, failureAnalysis, reportGeneration);
        assertThat(requestCaptor.getValue().taskId()).isEqualTo("task-1");
        assertThat(requestCaptor.getValue().sessionId()).isEqualTo("session-1");
        assertThat(requestCaptor.getValue().targetApiSpecIds()).containsExactly("api-1", "api-2");
        assertThat(requestCaptor.getValue().generationMode()).isEqualTo(TestCaseGenerationMode.BATCH);
        assertThat(outcome.summary()).isEqualTo("Test case generation completed; drafts=1 warnings=0");
        assertThat(outcome.resultRefs()).containsExactly("draft-1");
    }

    @Test
    void routesExecutionThroughHttpExecutionServiceUsingTaskMetadata() {
        when(httpExecution.execute(any()))
            .thenReturn(new HttpExecutionResult(
                "task-1",
                "staging",
                ExecutionMode.BATCH,
                List.of(new HttpExecutionCaseResult("case-1", "exec-1", HttpExecutionOutcomeStatus.PASSED, 12L, 200, "ok")),
                new HttpExecutionCounts(1, 1, 0, 0, 0, 0)
            ));

        var outcome = runner.run(task(), step(PlanStepType.EXECUTE_BATCH));

        var requestCaptor = ArgumentCaptor.forClass(HttpExecutionRequest.class);
        verify(httpExecution).execute(requestCaptor.capture());
        verifyNoInteractions(apiAnalysis, knowledgeRetrieval, testCaseGeneration, failureAnalysis, reportGeneration);
        assertThat(requestCaptor.getValue().taskId()).isEqualTo("task-1");
        assertThat(requestCaptor.getValue().selectedCaseIds()).containsExactly("case-1", "case-2");
        assertThat(requestCaptor.getValue().executionMode()).isEqualTo(ExecutionMode.BATCH);
        assertThat(requestCaptor.getValue().environment()).isEqualTo("staging");
        assertThat(requestCaptor.getValue().dryRun()).isTrue();
        assertThat(requestCaptor.getValue().environmentVariables()).containsEntry("baseUrl", "https://api.example.test");
        assertThat(outcome.summary()).isEqualTo("HTTP execution completed; total=1 passed=1 failed=0 error=0 blocked=0 skipped=0");
        assertThat(outcome.resultRefs()).containsExactly("exec-1");
    }

    @Test
    void routesFailureAnalysisAndReportGenerationThroughExistingServices() {
        when(failureAnalysis.analyzeTask(any()))
            .thenReturn(new TaskFailureAnalysisResult(
                "task-1",
                FailureAnalysisMode.BASIC,
                new TaskFailureAnalysisCounts(0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of()),
                List.of(),
                List.<GroupedFailureSummary>of()
            ));
        when(reportGeneration.generateTaskReport(any()))
            .thenReturn(new ReportGenerationResult("report-1", "task-1", "COMPLETE", 2, 1));

        var analysisOutcome = runner.run(task(), step(PlanStepType.ANALYZE_FAILURE));
        var reportOutcome = runner.run(task(), step(PlanStepType.GENERATE_REPORT));

        verify(failureAnalysis).analyzeTask(TaskFailureAnalysisRequest.basic("task-1", List.of("exec-1", "exec-2")));
        verify(reportGeneration).generateTaskReport(ReportGenerationRequest.forTask("task-1"));
        verifyNoInteractions(apiAnalysis, knowledgeRetrieval, testCaseGeneration, httpExecution);
        assertThat(analysisOutcome.summary()).isEqualTo("Failure analysis completed; executions=0 groupedFailures=0");
        assertThat(reportOutcome.taskStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(reportOutcome.summary()).isEqualTo("Report generated; state=COMPLETE cases=2 executions=1");
        assertThat(reportOutcome.resultRefs()).containsExactly("report-1");
    }

    @Test
    void stepOutcomeRemainsRuntimeStateAndIsNotObservationPersistence() {
        var fieldTypes = Arrays.stream(DefaultPlanStepRunner.class.getDeclaredFields())
            .map(field -> field.getType())
            .map(Class::getName)
            .toList();

        assertThat(fieldTypes).doesNotContain(ObservationRepository.class.getName());
    }

    private Task task() {
        var task = new Task();
        task.setTaskId("task-1");
        task.setTaskName("Explore order APIs");
        task.setSourceRef("material-1");
        task.setCreator("phase9-test");
        task.setTargetApiSpecIds(List.of("api-1", "api-2"));
        task.setMetadata(Map.of(
            "sessionId", "session-1",
            "selectedCaseIds", List.of("case-1", "case-2"),
            "executionIds", List.of("exec-1", "exec-2"),
            "environment", "staging",
            "dryRun", true,
            "environmentVariables", Map.of("baseUrl", "https://api.example.test")
        ));
        return task;
    }

    private PlanStep step(PlanStepType stepType) {
        var step = new PlanStep();
        step.setStepType(stepType);
        return step;
    }
}
