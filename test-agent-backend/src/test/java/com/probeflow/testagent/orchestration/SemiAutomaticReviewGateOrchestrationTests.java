package com.probeflow.testagent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.analysis.ApiAnalysisApplicationService;
import com.probeflow.testagent.analysis.ApiAnalysisResult;
import com.probeflow.testagent.httpexecution.HttpExecutionApplicationService;
import com.probeflow.testagent.httpexecution.HttpExecutionCaseResult;
import com.probeflow.testagent.httpexecution.HttpExecutionCounts;
import com.probeflow.testagent.httpexecution.HttpExecutionOutcomeStatus;
import com.probeflow.testagent.httpexecution.HttpExecutionRequest;
import com.probeflow.testagent.httpexecution.HttpExecutionResult;
import com.probeflow.testagent.knowledge.KnowledgeContext;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalApplicationService;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalResult;
import com.probeflow.testagent.report.ReportGenerationApplicationService;
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
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationApplicationService;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationCounts;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationMode;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationResult;
import com.probeflow.testagent.testcasegeneration.TestCasePromotionService;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import com.probeflow.testagent.testcasedraft.TestCaseDraft;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
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
class SemiAutomaticReviewGateOrchestrationTests {

    @Autowired
    private TaskInitializationService taskInitializationService;

    @Autowired
    private TaskOrchestrationApplicationService taskOrchestrationApplicationService;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private PlanStepRepository planSteps;

    @Autowired
    private TestCaseDraftRepository drafts;

    @Autowired
    private EntityManager entityManager;

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
    private ReportGenerationApplicationService reportGeneration;

    @Test
    void manualApiTestPausesAfterCaseGenerationAndDoesNotExecuteWhileReviewPending() {
        seedCommonMocks();
        var taskId = initializeManualApiTestTask();

        var first = taskOrchestrationApplicationService.runInitializedTask(taskId);
        var second = taskOrchestrationApplicationService.runInitializedTask(taskId);

        assertThat(first.finalStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
        assertThat(first.blockerDetails()).containsExactly("Waiting for manual review of 1 generated draft(s)");
        assertThat(second.finalStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
        assertThat(second.blockerDetails()).containsExactly("Waiting for manual review of 1 draft(s)");
        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.WAITING_FOR_REVIEW);
        assertThat(planSteps.findByTaskIdOrderByStepOrderAsc(taskId))
            .extracting(step -> step.getStepType(), step -> step.getStepStatus())
            .containsExactly(
                tuple(PlanStepType.ANALYZE_CODE_API, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.RETRIEVE_KNOWLEDGE, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.GENERATE_CASES, PlanStepStatus.SUCCESS),
                tuple(PlanStepType.EXECUTE_BATCH, PlanStepStatus.PENDING),
                tuple(PlanStepType.ANALYZE_FAILURE, PlanStepStatus.PENDING),
                tuple(PlanStepType.GENERATE_REPORT, PlanStepStatus.PENDING)
            );
        verifyNoInteractions(httpExecution, testCasePromotion, reportGeneration);
    }

    @Test
    void manualApiTestResumesAfterPromotedDraftsAndLeavesDiscardedDraftsOutOfExecution() {
        seedCommonMocks();
        when(httpExecution.execute(any()))
            .thenReturn(new HttpExecutionResult(
                "task-1",
                "staging",
                ExecutionMode.BATCH,
                List.of(new HttpExecutionCaseResult("case-approved", "exec-1", HttpExecutionOutcomeStatus.PASSED, 10L, 200, "ok")),
                new HttpExecutionCounts(1, 1, 0, 0, 0, 0)
            ));
        when(reportGeneration.generateTaskReport(any()))
            .thenReturn(new ReportGenerationResult("report-manual", "task-1", "COMPLETE", 1, 1));
        var taskId = initializeManualApiTestTask();
        taskOrchestrationApplicationService.runInitializedTask(taskId);
        drafts.save(reviewedDraft(taskId, "draft-1", DraftStatus.PROMOTED, "case-approved"));
        drafts.save(reviewedDraft(taskId, "draft-discarded", DraftStatus.DISCARDED, null));
        entityManager.flush();

        var resumed = taskOrchestrationApplicationService.runInitializedTask(taskId);

        assertThat(resumed.finalStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(resumed.reportId()).isEqualTo("report-manual");
        var httpRequest = ArgumentCaptor.forClass(HttpExecutionRequest.class);
        verify(httpExecution).execute(httpRequest.capture());
        assertThat(httpRequest.getValue().selectedCaseIds()).containsExactly("case-approved");
        assertThat(httpRequest.getValue().selectedCaseIds()).doesNotContain("draft-discarded");
        verify(testCasePromotion, never()).promote(any());
        assertThat(drafts.findById("draft-discarded").orElseThrow().getStatus()).isEqualTo(DraftStatus.DISCARDED);
        assertThat(tasks.findById(taskId).orElseThrow().getMetadata())
            .containsEntry("selectedCaseIds", List.of("case-approved"))
            .containsEntry("discardedDraftIds", List.of("draft-discarded"));
    }

    private void seedCommonMocks() {
        when(apiAnalysis.analyze(any()))
            .thenReturn(new ApiAnalysisResult("material-1", "analysis-task-1", true, "OPENAPI", null, null, List.of("api-1")));
        when(knowledgeRetrieval.retrieveForApiSpec(any(), any()))
            .thenReturn(new KnowledgeRetrievalResult("query", List.of(), KnowledgeContext.empty(true, true), 0.0d, 0, 0, true));
        when(testCaseGeneration.generate(any()))
            .thenReturn(new TestCaseGenerationResult(
                "task-1",
                "session-manual",
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
    }

    private String initializeManualApiTestTask() {
        return taskInitializationService.initialize(new TaskInitializationRequest(
            null,
            TaskType.API_TEST,
            "Explore order APIs",
            TaskSourceType.OPENAPI,
            "material-1",
            PromotionMode.MANUAL,
            List.of("api-1"),
            List.of(),
            TaskPriority.HIGH,
            "phase9-test",
            Map.of(
                "sessionId", "session-manual",
                "environment", "staging",
                "dryRun", true,
                "environmentVariables", Map.of("baseUrl", "https://api.example.test")
            )
        )).taskId();
    }

    private TestCaseDraft reviewedDraft(String taskId, String draftId, DraftStatus status, String promotedCaseId) {
        var draft = new TestCaseDraft();
        draft.setDraftId(draftId);
        draft.setTaskId(taskId);
        draft.setSource(CaseSource.STRUCTURE);
        draft.setStage(CaseSource.STRUCTURE);
        draft.setStatus(status);
        draft.setPromotionMode(PromotionMode.MANUAL);
        draft.setTargetApiSpecId("api-1");
        draft.setDedupKey("dedup-" + draftId);
        draft.setExpectedStatusCode(200);
        draft.setDraftContent(Map.of("title", draftId));
        draft.setPromotedCaseId(promotedCaseId);
        return draft;
    }
}
