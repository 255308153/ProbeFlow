package com.probeflow.testagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.ExecutorType;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecution;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionRepository;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionStatus;
import com.probeflow.testagent.testcase.CaseCategory;
import com.probeflow.testagent.testcase.CasePriority;
import com.probeflow.testagent.testcase.CaseRiskLevel;
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcase.CaseStatus;
import com.probeflow.testagent.testcase.DetailType;
import com.probeflow.testagent.testcase.TestCase;
import com.probeflow.testagent.testcase.TestCaseMode;
import com.probeflow.testagent.testcase.TestCaseRepository;
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
class ReportGenerationApplicationServiceTests {

    @Autowired
    private ReportGenerationApplicationService reportGeneration;

    @Autowired
    private ReportRepository reports;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TestCaseRepository testCases;

    @Autowired
    private TaskCaseExecutionRepository taskCaseExecutions;

    @Autowired
    private ExecutionRecordRepository executionRecords;

    @Autowired
    private EntityManager entityManager;

    @Test
    void generatesPersistedNoCasesReportSnapshotForEmptyTask() {
        var task = tasks.save(newTask("task-empty", List.of(), Map.of("environment", "staging")));
        entityManager.flush();
        entityManager.clear();

        var result = reportGeneration.generateTaskReport(ReportGenerationRequest.forTask(task.getTaskId()));

        assertThat(result.taskId()).isEqualTo(task.getTaskId());
        assertThat(result.reportId()).isNotBlank();
        assertThat(result.state()).isEqualTo("NO_CASES");
        assertThat(result.caseCount()).isZero();
        assertThat(result.executionCount()).isZero();

        var report = reports.findById(result.reportId()).orElseThrow();
        assertThat(report.getTaskId()).isEqualTo(task.getTaskId());
        assertThat(report.getCaseCount()).isZero();
        assertThat(report.getPassCount()).isZero();
        assertThat(report.getFailCount()).isZero();
        assertThat(report.getWarningCount()).isZero();
        assertThat(report.getSummary()).contains("state=NO_CASES", "cases=0", "executions=0");
        assertThat(report.getRiskSummary()).contains("No test cases");
        assertThat(report.getFindings()).singleElement()
            .satisfies(finding -> assertThat(finding)
                .containsEntry("type", "REPORT_STATE")
                .containsEntry("state", "NO_CASES")
                .containsEntry("caseCount", 0)
                .containsEntry("executionCount", 0)
                .containsEntry("evidenceType", "FACTUAL"));
        assertThat(report.getSuggestions()).isEmpty();
        assertThat(report.getMetadata())
            .containsEntry("schemaVersion", "phase8.v1")
            .containsEntry("reportType", "TASK_REPORT")
            .containsEntry("state", "NO_CASES")
            .containsEntry("scope", "HTTP_API_TESTING")
            .containsEntry("environment", "staging");
        assertThat((Map<String, Object>) report.getMetadata().get("executionSummary"))
            .containsEntry("total", 0)
            .containsEntry("executionCount", 0)
            .containsEntry("passed", 0)
            .containsEntry("failed", 0)
            .containsEntry("warning", 0)
            .containsEntry("error", 0)
            .containsEntry("blocked", 0)
            .containsEntry("skipped", 0)
            .containsEntry("unexecuted", 0)
            .containsEntry("passRate", "0.0000")
            .containsEntry("totalDurationMs", 0L);
        assertThat((Map<String, Object>) report.getMetadata().get("task"))
            .containsEntry("taskId", task.getTaskId())
            .containsEntry("taskName", "Phase 8 report task")
            .containsEntry("sourceType", "MANUAL");
    }

    @Test
    void reportsNoExecutionsWhenTaskHasLinkedCasesButNoExecutionRecords() {
        var task = tasks.save(newTask("task-no-exec", List.of("api-create", "api-read"), Map.of("environment", "qa")));
        var createCase = testCases.save(newTestCase("case-create", "api-create"));
        var readCase = testCases.save(newTestCase("case-read", "api-read"));
        entityManager.flush();
        entityManager.clear();

        var result = reportGeneration.generateTaskReport(ReportGenerationRequest.forTask(task.getTaskId()));

        assertThat(result.state()).isEqualTo("NO_EXECUTIONS");
        assertThat(result.caseCount()).isEqualTo(2);
        assertThat(result.executionCount()).isZero();

        var report = reports.findById(result.reportId()).orElseThrow();
        assertThat(report.getCaseCount()).isEqualTo(2);
        assertThat(report.getRiskSummary()).contains("no executions");
        assertThat(report.getFindings()).singleElement()
            .satisfies(finding -> assertThat(finding)
                .containsEntry("state", "NO_EXECUTIONS")
                .containsEntry("caseCount", 2)
                .containsEntry("unexecutedCaseIds", List.of(createCase.getCaseId(), readCase.getCaseId())));
        assertThat((List<String>) report.getMetadata().get("caseIds"))
            .containsExactly(createCase.getCaseId(), readCase.getCaseId());
        assertThat(report.getMetadata()).containsEntry("environment", "qa");
        assertThat((Map<String, Object>) report.getMetadata().get("executionSummary"))
            .containsEntry("total", 2)
            .containsEntry("executionCount", 0)
            .containsEntry("unexecuted", 2)
            .containsEntry("passRate", "0.0000");
        assertThat((List<String>) ((Map<String, Object>) report.getMetadata().get("executionSummary")).get("unexecutedCaseIds"))
            .containsExactly(createCase.getCaseId(), readCase.getCaseId());
        assertThat((Map<String, Object>) report.getMetadata().get("coverage"))
            .containsEntry("targetApiSpecIds", List.of("api-create", "api-read"))
            .containsEntry("testedApiSpecIds", List.of())
            .containsEntry("untestedApiSpecIds", List.of("api-create", "api-read"))
            .containsEntry("coverageRate", "0.0000");

        entityManager.flush();
        entityManager.clear();
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.ANALYZING_RESULTS);
        assertThat(testCases.findById(createCase.getCaseId()).orElseThrow().getTitle()).isEqualTo("Generated case case-create");
        assertThat(testCases.findById(readCase.getCaseId()).orElseThrow().getTitle()).isEqualTo("Generated case case-read");
    }

    @Test
    void basicSnapshotUsesExecutionEnvironmentAndDoesNotMutateExecutionState() {
        var task = tasks.save(newTask("task-executed", List.of("api-create"), Map.of("environment", "qa")));
        var testCase = testCases.save(newTestCase("case-create", "api-create"));
        var execution = executionRecords.save(newExecutionRecord(task.getTaskId(), testCase.getCaseId(), "prod"));
        var taskCaseExecution = taskCaseExecutions.save(newTaskCaseExecution(
            task.getTaskId(),
            testCase.getCaseId(),
            execution.getExecutionId()
        ));
        entityManager.flush();
        entityManager.clear();

        var result = reportGeneration.generateTaskReport(ReportGenerationRequest.forTask(task.getTaskId()));

        assertThat(result.state()).isEqualTo("BASIC_SNAPSHOT");
        assertThat(result.caseCount()).isEqualTo(1);
        assertThat(result.executionCount()).isEqualTo(1);

        var report = reports.findById(result.reportId()).orElseThrow();
        assertThat(report.getCaseCount()).isEqualTo(1);
        assertThat(report.getPassCount()).isEqualTo(1);
        assertThat(report.getFailCount()).isZero();
        assertThat(report.getWarningCount()).isZero();
        assertThat(report.getSummary()).contains("passRate=1.0000", "unexecuted=0");
        assertThat(report.getRiskSummary()).contains("All executed records passed");
        assertThat(report.getMetadata()).containsEntry("environment", "prod");
        assertThat((List<String>) report.getMetadata().get("environments")).containsExactly("prod", "qa");
        assertThat((List<String>) report.getMetadata().get("executionIds")).containsExactly(execution.getExecutionId());
        assertThat(report.getMetadata()).containsEntry("taskCaseExecutionCount", 1);
        assertThat((Map<String, Object>) report.getMetadata().get("executionSummary"))
            .containsEntry("total", 1)
            .containsEntry("executionCount", 1)
            .containsEntry("passed", 1)
            .containsEntry("failed", 0)
            .containsEntry("warning", 0)
            .containsEntry("error", 0)
            .containsEntry("blocked", 0)
            .containsEntry("skipped", 0)
            .containsEntry("unexecuted", 0)
            .containsEntry("passRate", "1.0000")
            .containsEntry("totalDurationMs", 42L);
        assertThat((Map<String, Object>) report.getMetadata().get("coverage"))
            .containsEntry("testedApiSpecIds", List.of("api-create"))
            .containsEntry("untestedApiSpecIds", List.of())
            .containsEntry("coverageRate", "1.0000");
        assertThat(report.getFindings()).singleElement()
            .satisfies(finding -> assertThat(finding).containsEntry("state", "BASIC_SNAPSHOT"));

        entityManager.flush();
        entityManager.clear();
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.ANALYZING_RESULTS);
        assertThat(testCases.findById(testCase.getCaseId()).orElseThrow().getStatus()).isEqualTo(CaseStatus.READY);
        assertThat(taskCaseExecutions.findById(taskCaseExecution.getId()).orElseThrow().getExecutionStatus())
            .isEqualTo(TaskCaseExecutionStatus.COMPLETED);
        assertThat(executionRecords.findById(execution.getExecutionId()).orElseThrow().getOverallStatus())
            .isEqualTo(OverallStatus.PASSED);
    }

    @Test
    void aggregatesMixedExecutionOutcomesCoverageDurationAndModes() {
        var apiSpecIds = List.of("api-pass", "api-fail", "api-warning", "api-error", "api-blocked", "api-skipped", "api-unexecuted");
        var task = tasks.save(newTask("task-mixed", apiSpecIds, Map.of("environment", "qa")));
        var passedCase = testCases.save(newTestCase("case-pass", "api-pass"));
        var failedCase = testCases.save(newTestCase("case-fail", "api-fail"));
        var warningCase = testCases.save(newTestCase("case-warning", "api-warning"));
        var errorCase = testCases.save(newTestCase("case-error", "api-error"));
        var blockedCase = testCases.save(newTestCase("case-blocked", "api-blocked"));
        var skippedCase = testCases.save(newTestCase("case-skipped", "api-skipped"));
        var unexecutedCase = testCases.save(newTestCase("case-unexecuted", "api-unexecuted"));

        var passed = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            passedCase.getCaseId(),
            "qa",
            "api-pass",
            OverallStatus.PASSED,
            12L
        ));
        var failed = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            failedCase.getCaseId(),
            "qa",
            "api-fail",
            OverallStatus.FAILED,
            75L
        ));
        var warning = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            warningCase.getCaseId(),
            "qa",
            "api-warning",
            OverallStatus.PASSED_WITH_WARNINGS,
            99L
        ));
        var error = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            errorCase.getCaseId(),
            "qa",
            "api-error",
            OverallStatus.ERROR,
            30L
        ));
        var blocked = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            blockedCase.getCaseId(),
            "qa",
            "api-blocked",
            OverallStatus.BLOCKED,
            1L
        ));
        var skipped = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            skippedCase.getCaseId(),
            "qa",
            "api-skipped",
            OverallStatus.SKIPPED,
            0L
        ));
        taskCaseExecutions.save(newTaskCaseExecution(task.getTaskId(), passedCase.getCaseId(), passed.getExecutionId(), ExecutionMode.SINGLE));
        taskCaseExecutions.save(newTaskCaseExecution(task.getTaskId(), failedCase.getCaseId(), failed.getExecutionId(), ExecutionMode.BATCH));
        taskCaseExecutions.save(newTaskCaseExecution(task.getTaskId(), warningCase.getCaseId(), warning.getExecutionId(), ExecutionMode.SUITE_STEP));
        taskCaseExecutions.save(newTaskCaseExecution(task.getTaskId(), errorCase.getCaseId(), error.getExecutionId(), ExecutionMode.BATCH));
        taskCaseExecutions.save(newTaskCaseExecution(task.getTaskId(), blockedCase.getCaseId(), blocked.getExecutionId(), ExecutionMode.SUITE_STEP));
        taskCaseExecutions.save(newTaskCaseExecution(task.getTaskId(), skippedCase.getCaseId(), skipped.getExecutionId(), ExecutionMode.SINGLE));
        entityManager.flush();
        entityManager.clear();

        var result = reportGeneration.generateTaskReport(ReportGenerationRequest.forTask(task.getTaskId()));

        assertThat(result.state()).isEqualTo("BASIC_SNAPSHOT");
        assertThat(result.caseCount()).isEqualTo(7);
        assertThat(result.executionCount()).isEqualTo(6);

        var report = reports.findById(result.reportId()).orElseThrow();
        assertThat(report.getCaseCount()).isEqualTo(7);
        assertThat(report.getPassCount()).isEqualTo(1);
        assertThat(report.getFailCount()).isEqualTo(1);
        assertThat(report.getWarningCount()).isEqualTo(1);
        assertThat(report.getSummary()).contains("passRate=0.1667", "unexecuted=1");
        assertThat(report.getRiskSummary()).contains("blocking risk", "error=1", "blocked=1", "failed=1");

        @SuppressWarnings("unchecked")
        var executionSummary = (Map<String, Object>) report.getMetadata().get("executionSummary");
        assertThat(executionSummary)
            .containsEntry("total", 7)
            .containsEntry("executionCount", 6)
            .containsEntry("passed", 1)
            .containsEntry("failed", 1)
            .containsEntry("warning", 1)
            .containsEntry("error", 1)
            .containsEntry("blocked", 1)
            .containsEntry("skipped", 1)
            .containsEntry("unexecuted", 1)
            .containsEntry("passRate", "0.1667")
            .containsEntry("totalDurationMs", 217L);
        assertThat((List<String>) executionSummary.get("unexecutedCaseIds"))
            .containsExactly(unexecutedCase.getCaseId());
        assertThat((List<Map<String, Object>>) executionSummary.get("slowestCases"))
            .extracting(entry -> entry.get("caseId"))
            .containsExactly(
                warningCase.getCaseId(),
                failedCase.getCaseId(),
                errorCase.getCaseId(),
                passedCase.getCaseId(),
                blockedCase.getCaseId()
            );

        assertThat((Map<String, Object>) report.getMetadata().get("coverage"))
            .containsEntry("testedApiSpecIds", List.of("api-blocked", "api-error", "api-fail", "api-pass", "api-skipped", "api-warning"))
            .containsEntry("untestedApiSpecIds", List.of("api-unexecuted"))
            .containsEntry("totalTargetApiCount", 7)
            .containsEntry("testedTargetApiCount", 6)
            .containsEntry("coverageRate", "0.8571");
        assertThat((Map<String, Object>) report.getMetadata().get("executionModes"))
            .containsEntry("SINGLE", 2)
            .containsEntry("BATCH", 2)
            .containsEntry("SUITE_STEP", 2);
        assertThat(report.getFindings()).singleElement()
            .satisfies(finding -> assertThat(finding)
                .containsEntry("state", "BASIC_SNAPSHOT")
                .containsEntry("unexecutedCaseIds", List.of(unexecutedCase.getCaseId())));
    }

    @Test
    void missingTaskIsRejectedClearly() {
        assertThatThrownBy(() -> reportGeneration.generateTaskReport(ReportGenerationRequest.forTask("missing-task")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Task not found: missing-task");
    }

    private Task newTask(String taskId, List<String> apiSpecIds, Map<String, Object> metadata) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Phase 8 report task");
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("manual");
        task.setTargetApiSpecIds(apiSpecIds);
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.NOT_REQUIRED);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase8-test");
        task.setMetadata(metadata);
        return task;
    }

    private TestCase newTestCase(String caseId, String apiSpecId) {
        var testCase = new TestCase();
        testCase.setCaseId(caseId);
        testCase.setPrimaryApiSpecId(apiSpecId);
        testCase.setCaseCategory(CaseCategory.API);
        testCase.setMode(TestCaseMode.SINGLE);
        testCase.setTitle("Generated case " + caseId);
        testCase.setDescription("Generated case");
        testCase.setPreconditions(List.of());
        testCase.setExpectedResult("Request succeeds");
        testCase.setPriority(CasePriority.MEDIUM);
        testCase.setRiskLevel(CaseRiskLevel.MEDIUM);
        testCase.setTags(List.of("phase8"));
        testCase.setStatus(CaseStatus.READY);
        testCase.setSource(CaseSource.MANUAL);
        testCase.setDetailType(DetailType.API);
        testCase.setDetail(Map.of());
        testCase.setSteps(List.of());
        testCase.setBasedOnApiSpecVersions(Map.of(apiSpecId, 1));
        testCase.setGeneratedFromSingleCaseIds(List.of());
        return testCase;
    }

    private ExecutionRecord newExecutionRecord(String taskId, String caseId, String environment) {
        return newExecutionRecord(taskId, caseId, environment, "api-create", OverallStatus.PASSED, 42L);
    }

    private ExecutionRecord newExecutionRecord(
        String taskId,
        String caseId,
        String environment,
        String apiSpecId,
        OverallStatus overallStatus,
        long durationMs
    ) {
        var record = new ExecutionRecord();
        record.setTaskId(taskId);
        record.setCaseId(caseId);
        record.setExecutorType(ExecutorType.HTTP);
        record.setEnvironment(environment);
        record.setRequestSnapshot(Map.of("method", "GET", "path", "/api/orders", "apiSpecId", apiSpecId));
        record.setResponseSnapshot(Map.of("statusCode", 200));
        record.setAssertionResults(List.of());
        record.setOverallStatus(overallStatus);
        record.setCriticalFailed(overallStatus == OverallStatus.FAILED
            || overallStatus == OverallStatus.ERROR
            || overallStatus == OverallStatus.BLOCKED);
        record.setDurationMs(durationMs);
        record.setStatusCode(200);
        return record;
    }

    private TaskCaseExecution newTaskCaseExecution(String taskId, String caseId, String executionId) {
        return newTaskCaseExecution(taskId, caseId, executionId, ExecutionMode.SINGLE);
    }

    private TaskCaseExecution newTaskCaseExecution(
        String taskId,
        String caseId,
        String executionId,
        ExecutionMode executionMode
    ) {
        var execution = new TaskCaseExecution();
        execution.setTaskId(taskId);
        execution.setCaseId(caseId);
        execution.setExecutionMode(executionMode);
        execution.setExecutionStatus(TaskCaseExecutionStatus.COMPLETED);
        execution.setExecutionRecordId(executionId);
        execution.setSnapshotJson(Map.of("caseId", caseId, "path", "/api/orders"));
        return execution;
    }
}
