package com.probeflow.testagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.ExecutorType;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.observation.AnalysisLevel;
import com.probeflow.testagent.observation.Observation;
import com.probeflow.testagent.observation.ObservationRepository;
import com.probeflow.testagent.observation.ObservationRiskLevel;
import com.probeflow.testagent.observation.ObservationSource;
import com.probeflow.testagent.observation.ObservationType;
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
    private ApiSpecRepository apiSpecs;

    @Autowired
    private TaskCaseExecutionRepository taskCaseExecutions;

    @Autowired
    private ExecutionRecordRepository executionRecords;

    @Autowired
    private ObservationRepository observations;

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
        apiSpecs.save(newApiSpec("api-create", HttpMethod.GET, "/api/orders"));
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
        apiSpecIds.forEach(apiSpecId -> apiSpecs.save(newApiSpec(apiSpecId, HttpMethod.GET, "/api/" + apiSpecId)));
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
        assertThat(report.getFindings()).anySatisfy(finding -> assertThat(finding)
                .containsEntry("state", "BASIC_SNAPSHOT")
                .containsEntry("unexecutedCaseIds", List.of(unexecutedCase.getCaseId())));
    }

    @Test
    void generatesStructuredFindingsFromExecutionFactsObservationsAndMissingLinks() {
        apiSpecs.save(newApiSpec("api-auth", HttpMethod.GET, "/api/private"));
        apiSpecs.save(newApiSpec("api-validation", HttpMethod.POST, "/api/orders"));
        apiSpecs.save(newApiSpec("api-server", HttpMethod.GET, "/api/orders/{id}"));
        apiSpecs.save(newApiSpec("api-blocked", HttpMethod.GET, "/api/blocked"));
        var task = tasks.save(newTask(
            "task-findings",
            List.of("api-auth", "api-validation", "api-server", "api-blocked", "api-missing"),
            Map.of("environment", "qa")
        ));
        var authCase = testCases.save(newTestCase("case-auth", "api-auth"));
        var validationCase = testCases.save(newTestCase("case-validation", "api-validation"));
        var serverCase = testCases.save(newTestCase("case-server", "api-server"));
        var blockedCase = testCases.save(newTestCase("case-blocked-finding", "api-blocked"));

        var auth = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            authCase.getCaseId(),
            "qa",
            "api-auth",
            OverallStatus.FAILED,
            18L,
            401,
            Map.of("statusCode", 401),
            null,
            List.of(Map.of("type", "STATUS_CODE", "status", "FAILED", "expected", 200, "actual", 401))
        ));
        var validation = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            validationCase.getCaseId(),
            "qa",
            "api-validation",
            OverallStatus.FAILED,
            21L,
            422,
            Map.of("statusCode", 422),
            null,
            List.of(Map.of("type", "JSON_FIELD_EQUALS", "status", "FAILED", "expected", "CREATED", "actual", "INVALID"))
        ));
        var server = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            serverCase.getCaseId(),
            "qa",
            "api-server",
            OverallStatus.FAILED,
            50L,
            500,
            Map.of("statusCode", 500, "failureType", "HTTP_5XX"),
            null,
            List.of()
        ));
        var blocked = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            blockedCase.getCaseId(),
            "qa",
            "api-blocked",
            OverallStatus.BLOCKED,
            0L,
            null,
            Map.of("errorType", "INVALID_REQUEST"),
            "Unresolved variable: token",
            List.of()
        ));
        var missing = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            "case-missing-link",
            "qa",
            "api-missing",
            OverallStatus.FAILED,
            9L,
            500,
            Map.of("statusCode", 500),
            null,
            List.of()
        ));
        var authObservation = observations.save(newObservation(
            task.getTaskId(),
            auth.getExecutionId(),
            ObservationType.RISK_EVALUATION,
            ObservationRiskLevel.CRITICAL,
            "Credentials rejected by target service",
            "HTTP 401 indicates authentication failure",
            "Refresh credentials before retrying"
        ));
        observations.save(newObservation(
            task.getTaskId(),
            server.getExecutionId(),
            ObservationType.RISK_EVALUATION,
            ObservationRiskLevel.HIGH,
            "Server error returned",
            "HTTP 500 indicates backend regression risk",
            "Escalate to service owner"
        ));
        entityManager.flush();
        entityManager.clear();

        var result = reportGeneration.generateTaskReport(ReportGenerationRequest.forTask(task.getTaskId()));

        var report = reports.findById(result.reportId()).orElseThrow();
        assertThat(report.getFindings()).hasSizeGreaterThanOrEqualTo(7);
        assertThat(report.getFindings().getFirst())
            .containsEntry("classification", "AUTH_ISSUE")
            .containsEntry("severity", "CRITICAL")
            .containsEntry("evidenceType", "FACTUAL_AND_INFERRED")
            .containsEntry("caseId", authCase.getCaseId())
            .containsEntry("primaryApiSpecId", "api-auth")
            .containsEntry("executionId", auth.getExecutionId())
            .containsEntry("observationIds", List.of(authObservation.getObservationId()))
            .containsEntry("retryable", false);
        assertThat((Map<String, Object>) report.getFindings().getFirst().get("sourceReferences"))
            .containsEntry("executionIds", List.of(auth.getExecutionId()))
            .containsEntry("observationIds", List.of(authObservation.getObservationId()))
            .containsEntry("caseIds", List.of(authCase.getCaseId()))
            .containsEntry("apiSpecIds", List.of("api-auth"));
        assertThat((List<String>) report.getFindings().getFirst().get("factualEvidence"))
            .contains("overallStatus=FAILED", "statusCode=401", "classification=AUTH_ISSUE");
        assertThat((List<Map<String, Object>>) report.getFindings().getFirst().get("inferredEvidence"))
            .singleElement()
            .satisfies(evidence -> assertThat(evidence)
                .containsEntry("observationId", authObservation.getObservationId())
                .containsEntry("riskLevel", "CRITICAL")
                .containsEntry("source", "SYSTEM"));

        assertThat(report.getFindings()).anySatisfy(finding -> assertThat(finding)
            .containsEntry("classification", "VALIDATION_ISSUE")
            .containsEntry("caseId", validationCase.getCaseId())
            .containsEntry("primaryApiSpecId", "api-validation")
            .containsEntry("executionId", validation.getExecutionId()));
        assertThat(report.getFindings()).anySatisfy(finding -> assertThat(finding)
            .containsEntry("classification", "SERVER_ERROR")
            .containsEntry("severity", "HIGH")
            .containsEntry("retryable", true)
            .containsEntry("caseId", serverCase.getCaseId())
            .containsEntry("primaryApiSpecId", "api-server")
            .containsEntry("executionId", server.getExecutionId()));
        assertThat(report.getFindings()).anySatisfy(finding -> assertThat(finding)
            .containsEntry("classification", "ENVIRONMENT_ISSUE")
            .containsEntry("caseId", blockedCase.getCaseId())
            .containsEntry("primaryApiSpecId", "api-blocked")
            .containsEntry("executionId", blocked.getExecutionId()));
        assertThat(report.getFindings()).anySatisfy(finding -> assertThat(finding)
            .containsEntry("type", "MISSING_TEST_CASE")
            .containsEntry("classification", "DATA_QUALITY_ISSUE")
            .containsEntry("caseId", "case-missing-link")
            .containsEntry("executionId", missing.getExecutionId()));
        assertThat(report.getFindings()).anySatisfy(finding -> assertThat(finding)
            .containsEntry("type", "MISSING_API_SPEC")
            .containsEntry("classification", "DATA_QUALITY_ISSUE")
            .containsEntry("primaryApiSpecId", "api-missing")
            .containsEntry("executionId", missing.getExecutionId()));
    }

    @Test
    void buildsDeduplicatedPrioritizedMachineReadableSuggestions() {
        apiSpecs.save(newApiSpec("api-auth-suggestion", HttpMethod.GET, "/api/private"));
        apiSpecs.save(newApiSpec("api-server-suggestion", HttpMethod.GET, "/api/orders/{id}"));
        apiSpecs.save(newApiSpec("api-validation-suggestion", HttpMethod.POST, "/api/orders"));
        var task = tasks.save(newTask(
            "task-suggestions",
            List.of("api-auth-suggestion", "api-server-suggestion", "api-validation-suggestion"),
            Map.of("environment", "qa")
        ));
        var authCase = testCases.save(newTestCase("case-auth-suggestion", "api-auth-suggestion"));
        var firstServerCase = testCases.save(newTestCase("case-server-suggestion-1", "api-server-suggestion"));
        var secondServerCase = testCases.save(newTestCase("case-server-suggestion-2", "api-server-suggestion"));
        var validationCase = testCases.save(newTestCase("case-validation-suggestion", "api-validation-suggestion"));
        var auth = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            authCase.getCaseId(),
            "qa",
            "api-auth-suggestion",
            OverallStatus.FAILED,
            11L,
            403,
            Map.of("statusCode", 403),
            null,
            List.of()
        ));
        var firstServer = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            firstServerCase.getCaseId(),
            "qa",
            "api-server-suggestion",
            OverallStatus.FAILED,
            41L,
            500,
            Map.of("statusCode", 500),
            null,
            List.of()
        ));
        var secondServer = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            secondServerCase.getCaseId(),
            "qa",
            "api-server-suggestion",
            OverallStatus.FAILED,
            42L,
            500,
            Map.of("statusCode", 500),
            null,
            List.of()
        ));
        var validation = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            validationCase.getCaseId(),
            "qa",
            "api-validation-suggestion",
            OverallStatus.FAILED,
            13L,
            422,
            Map.of("statusCode", 422),
            null,
            List.of(Map.of("type", "JSON_FIELD_EQUALS", "status", "FAILED", "expected", "CREATED", "actual", "INVALID"))
        ));
        entityManager.flush();
        entityManager.clear();

        var result = reportGeneration.generateTaskReport(ReportGenerationRequest.forTask(task.getTaskId()));

        var report = reports.findById(result.reportId()).orElseThrow();
        assertThat(report.getSuggestions()).hasSize(3);
        assertThat(report.getSuggestions()).extracting(suggestion -> suggestion.get("priority"))
            .containsExactly("P0", "P1", "P1");
        assertThat(report.getSuggestions().get(0))
            .containsEntry("category", "AUTH")
            .containsEntry("priority", "P0")
            .containsEntry("classification", "AUTH_ISSUE")
            .containsEntry("primaryApiSpecId", "api-auth-suggestion")
            .containsEntry("retryable", false);
        assertThat((String) report.getSuggestions().get(0).get("action"))
            .contains("credentials");

        var retrySuggestion = report.getSuggestions().get(1);
        assertThat(retrySuggestion)
            .containsEntry("category", "RETRY")
            .containsEntry("priority", "P1")
            .containsEntry("classification", "SERVER_ERROR")
            .containsEntry("primaryApiSpecId", "api-server-suggestion")
            .containsEntry("statusCode", 500)
            .containsEntry("retryable", true);
        assertThat((String) retrySuggestion.get("stableKey"))
            .isEqualTo("RETRY_AFTER_STABILIZATION|SERVER_ERROR|api-server-suggestion|500||");
        @SuppressWarnings("unchecked")
        var retryRefs = (Map<String, Object>) retrySuggestion.get("sourceReferences");
        assertThat((List<String>) retryRefs.get("executionIds"))
            .containsExactlyInAnyOrder(firstServer.getExecutionId(), secondServer.getExecutionId());
        assertThat((List<String>) retryRefs.get("caseIds"))
            .containsExactlyInAnyOrder(firstServerCase.getCaseId(), secondServerCase.getCaseId());
        assertThat((List<String>) retryRefs.get("apiSpecIds"))
            .containsExactly("api-server-suggestion");

        assertThat(report.getSuggestions().get(2))
            .containsEntry("category", "INVESTIGATION")
            .containsEntry("priority", "P1")
            .containsEntry("classification", "VALIDATION_ISSUE")
            .containsEntry("primaryApiSpecId", "api-validation-suggestion")
            .containsEntry("retryable", false);
        assertThat((String) report.getSuggestions().get(2).get("rationale"))
            .contains("non-retryable");
        assertThat(report.getFindings()).anySatisfy(finding -> assertThat(finding)
            .containsEntry("classification", "AUTH_ISSUE")
            .containsEntry("executionId", auth.getExecutionId()));
        assertThat(report.getFindings()).anySatisfy(finding -> assertThat(finding)
            .containsEntry("classification", "VALIDATION_ISSUE")
            .containsEntry("executionId", validation.getExecutionId()));
    }

    @Test
    void reusesExistingBasicObservationsAndSkipsIneligiblePassedExecutions() {
        apiSpecs.save(newApiSpec("api-reuse-failed", HttpMethod.GET, "/api/reuse-failed"));
        apiSpecs.save(newApiSpec("api-reuse-passed", HttpMethod.GET, "/api/reuse-passed"));
        var task = tasks.save(newTask(
            "task-analysis-reuse",
            List.of("api-reuse-failed", "api-reuse-passed"),
            Map.of("environment", "qa")
        ));
        var failedCase = testCases.save(newTestCase("case-analysis-reuse-failed", "api-reuse-failed"));
        var passedCase = testCases.save(newTestCase("case-analysis-reuse-passed", "api-reuse-passed"));
        var failed = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            failedCase.getCaseId(),
            "qa",
            "api-reuse-failed",
            OverallStatus.FAILED,
            25L,
            500,
            Map.of("statusCode", 500),
            null,
            List.of()
        ));
        var passed = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            passedCase.getCaseId(),
            "qa",
            "api-reuse-passed",
            OverallStatus.PASSED,
            8L
        ));
        var existingObservation = observations.save(newObservation(
            task.getTaskId(),
            failed.getExecutionId(),
            ObservationType.RISK_EVALUATION,
            ObservationRiskLevel.HIGH,
            "Existing server error analysis",
            "Existing analysis should be reused",
            "Escalate existing finding"
        ));
        entityManager.flush();
        entityManager.clear();

        var result = reportGeneration.generateTaskReport(ReportGenerationRequest.forTask(task.getTaskId()));

        assertThat(observations.findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
            failed.getExecutionId(),
            AnalysisLevel.BASIC
        )).singleElement()
            .satisfies(observation -> assertThat(observation.getObservationId()).isEqualTo(existingObservation.getObservationId()));
        assertThat(observations.findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
            passed.getExecutionId(),
            AnalysisLevel.BASIC
        )).isEmpty();

        var report = reports.findById(result.reportId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var analysisCoverage = (Map<String, Object>) report.getMetadata().get("analysisCoverage");
        assertThat(analysisCoverage)
            .containsEntry("mode", "BASIC")
            .containsEntry("deepAnalysisRequested", false)
            .containsEntry("eligibleExecutionCount", 1)
            .containsEntry("existingBasicAnalysisCount", 1)
            .containsEntry("generatedBasicAnalysisCount", 0)
            .containsEntry("missingBasicAnalysisCount", 0)
            .containsEntry("analysisComplete", true);
        assertThat((List<String>) analysisCoverage.get("existingAnalysisExecutionIds"))
            .containsExactly(failed.getExecutionId());
        assertThat((List<String>) analysisCoverage.get("ineligibleExecutionIds"))
            .containsExactly(passed.getExecutionId());
        assertThat(report.getFindings()).anySatisfy(finding -> assertThat(finding)
            .containsEntry("executionId", failed.getExecutionId())
            .containsEntry("observationIds", List.of(existingObservation.getObservationId()))
            .containsEntry("evidenceType", "FACTUAL_AND_INFERRED"));

        entityManager.flush();
        entityManager.clear();
        assertThat(executionRecords.findById(failed.getExecutionId()).orElseThrow().getOverallStatus())
            .isEqualTo(OverallStatus.FAILED);
        assertThat(testCases.findById(failedCase.getCaseId()).orElseThrow().getTitle())
            .isEqualTo("Generated case " + failedCase.getCaseId());
    }

    @Test
    void triggersMissingBasicAnalysisOnceForEligibleExecutionWithoutDeepOrLlmDependency() {
        apiSpecs.save(newApiSpec("api-analysis-missing", HttpMethod.GET, "/api/analysis-missing"));
        var task = tasks.save(newTask(
            "task-analysis-missing",
            List.of("api-analysis-missing"),
            Map.of("environment", "qa")
        ));
        var testCase = testCases.save(newTestCase("case-analysis-missing", "api-analysis-missing"));
        var failed = executionRecords.save(newExecutionRecord(
            task.getTaskId(),
            testCase.getCaseId(),
            "qa",
            "api-analysis-missing",
            OverallStatus.FAILED,
            44L,
            500,
            Map.of("statusCode", 500),
            null,
            List.of()
        ));
        entityManager.flush();
        entityManager.clear();

        var firstResult = reportGeneration.generateTaskReport(ReportGenerationRequest.forTask(task.getTaskId()));

        var generated = observations.findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
            failed.getExecutionId(),
            AnalysisLevel.BASIC
        );
        assertThat(generated).singleElement()
            .satisfies(observation -> {
                assertThat(observation.getAnalysisLevel()).isEqualTo(AnalysisLevel.BASIC);
                assertThat(observation.getSource()).isEqualTo(ObservationSource.SYSTEM);
                assertThat(observation.getSummary()).contains("BASIC failure analysis");
            });

        var firstReport = reports.findById(firstResult.reportId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var firstCoverage = (Map<String, Object>) firstReport.getMetadata().get("analysisCoverage");
        assertThat(firstCoverage)
            .containsEntry("mode", "BASIC")
            .containsEntry("deepAnalysisRequested", false)
            .containsEntry("eligibleExecutionCount", 1)
            .containsEntry("existingBasicAnalysisCount", 0)
            .containsEntry("generatedBasicAnalysisCount", 1)
            .containsEntry("missingBasicAnalysisCount", 0)
            .containsEntry("analysisComplete", true);
        assertThat((List<String>) firstCoverage.get("generatedAnalysisExecutionIds"))
            .containsExactly(failed.getExecutionId());
        assertThat((List<String>) firstCoverage.get("generatedObservationIds"))
            .containsExactly(generated.getFirst().getObservationId());
        assertThat(firstReport.getFindings()).anySatisfy(finding -> assertThat(finding)
            .containsEntry("executionId", failed.getExecutionId())
            .containsEntry("observationIds", List.of(generated.getFirst().getObservationId()))
            .containsEntry("evidenceType", "FACTUAL_AND_INFERRED"));

        var secondResult = reportGeneration.generateTaskReport(ReportGenerationRequest.forTask(task.getTaskId()));

        assertThat(observations.findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
            failed.getExecutionId(),
            AnalysisLevel.BASIC
        )).hasSize(1);
        var secondReport = reports.findById(secondResult.reportId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var secondCoverage = (Map<String, Object>) secondReport.getMetadata().get("analysisCoverage");
        assertThat(secondCoverage)
            .containsEntry("existingBasicAnalysisCount", 1)
            .containsEntry("generatedBasicAnalysisCount", 0)
            .containsEntry("deepAnalysisRequested", false);
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

    private ApiSpec newApiSpec(String apiSpecId, HttpMethod method, String path) {
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId(apiSpecId);
        apiSpec.setSystemName("ProbeFlow target");
        apiSpec.setModuleName("orders");
        apiSpec.setHttpMethod(method);
        apiSpec.setPath(path);
        apiSpec.setSummary(method + " " + path);
        apiSpec.setDescription("Phase 8 report test API");
        apiSpec.setOperationId(apiSpecId + "-operation");
        apiSpec.setSourceType(ApiSpecSourceType.MANUAL);
        apiSpec.setSourceRef("phase8-test");
        apiSpec.setSourceLocation(Map.of());
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(true);
        return apiSpec;
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
        return newExecutionRecord(
            taskId,
            caseId,
            environment,
            apiSpecId,
            overallStatus,
            durationMs,
            200,
            Map.of("statusCode", 200),
            null,
            List.of()
        );
    }

    private ExecutionRecord newExecutionRecord(
        String taskId,
        String caseId,
        String environment,
        String apiSpecId,
        OverallStatus overallStatus,
        long durationMs,
        Integer statusCode,
        Map<String, Object> responseSnapshot,
        String errorMessage,
        List<Map<String, Object>> assertionResults
    ) {
        var record = new ExecutionRecord();
        record.setTaskId(taskId);
        record.setCaseId(caseId);
        record.setExecutorType(ExecutorType.HTTP);
        record.setEnvironment(environment);
        record.setRequestSnapshot(Map.of("method", "GET", "path", "/api/orders", "apiSpecId", apiSpecId));
        record.setResponseSnapshot(responseSnapshot);
        record.setAssertionResults(assertionResults);
        record.setOverallStatus(overallStatus);
        record.setCriticalFailed(overallStatus == OverallStatus.FAILED
            || overallStatus == OverallStatus.ERROR
            || overallStatus == OverallStatus.BLOCKED);
        record.setDurationMs(durationMs);
        record.setStatusCode(statusCode);
        record.setErrorMessage(errorMessage);
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

    private Observation newObservation(
        String taskId,
        String executionId,
        ObservationType type,
        ObservationRiskLevel riskLevel,
        String summary,
        String failureReason,
        String nextSuggestion
    ) {
        var observation = new Observation();
        observation.setTaskId(taskId);
        observation.setExecutionId(executionId);
        observation.setObservationType(type);
        observation.setAnalysisLevel(AnalysisLevel.BASIC);
        observation.setSummary(summary);
        observation.setFailureReason(failureReason);
        observation.setRiskLevel(riskLevel);
        observation.setNextSuggestion(nextSuggestion);
        observation.setSource(ObservationSource.SYSTEM);
        return observation;
    }
}
