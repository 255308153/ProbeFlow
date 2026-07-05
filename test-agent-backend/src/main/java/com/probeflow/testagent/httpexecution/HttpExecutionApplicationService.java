package com.probeflow.testagent.httpexecution;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.ExecutorType;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecution;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionRepository;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionStatus;
import com.probeflow.testagent.testcase.TestCase;
import com.probeflow.testagent.testcase.TestCaseMode;
import com.probeflow.testagent.testcase.TestCaseRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class HttpExecutionApplicationService {

    private final TaskRepository tasks;
    private final TestCaseRepository testCases;
    private final ApiSpecRepository apiSpecs;
    private final ExecutionRecordRepository executionRecords;
    private final TaskCaseExecutionRepository taskCaseExecutions;
    private final HttpClientGateway httpClientGateway;
    private final ExecutableRequestBuilder executableRequestBuilder;
    private final HttpResponseSnapshotFactory responseSnapshotFactory;
    private final BaselineHttpAssertionChecker assertionChecker;

    public HttpExecutionApplicationService(
        TaskRepository tasks,
        TestCaseRepository testCases,
        ApiSpecRepository apiSpecs,
        ExecutionRecordRepository executionRecords,
        TaskCaseExecutionRepository taskCaseExecutions,
        HttpClientGateway httpClientGateway,
        ExecutableRequestBuilder executableRequestBuilder,
        HttpResponseSnapshotFactory responseSnapshotFactory,
        BaselineHttpAssertionChecker assertionChecker
    ) {
        this.tasks = tasks;
        this.testCases = testCases;
        this.apiSpecs = apiSpecs;
        this.executionRecords = executionRecords;
        this.taskCaseExecutions = taskCaseExecutions;
        this.httpClientGateway = httpClientGateway;
        this.executableRequestBuilder = executableRequestBuilder;
        this.responseSnapshotFactory = responseSnapshotFactory;
        this.assertionChecker = assertionChecker;
    }

    @Transactional
    public HttpExecutionResult execute(HttpExecutionRequest request) {
        validateRequest(request);
        var task = tasks.findById(request.taskId())
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + request.taskId()));
        if (request.executionMode() != ExecutionMode.SINGLE || request.selectedCaseIds().size() != 1) {
            throw new IllegalArgumentException("Issue 01 supports exactly one selected case in SINGLE mode");
        }

        var caseId = request.selectedCaseIds().getFirst();
        var testCase = testCases.findById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("TestCase not found: " + caseId));
        if (testCase.getMode() != TestCaseMode.SINGLE) {
            throw new IllegalArgumentException("Issue 01 supports only SINGLE TestCase execution: " + caseId);
        }

        var apiSpec = apiSpecs.findById(testCase.getPrimaryApiSpecId())
            .orElseThrow(() -> new IllegalArgumentException("ApiSpec not found: " + testCase.getPrimaryApiSpecId()));
        if (!task.getTargetApiSpecIds().isEmpty() && !task.getTargetApiSpecIds().contains(apiSpec.getApiSpecId())) {
            throw new IllegalArgumentException("TestCase " + caseId + " is not selected by Task " + task.getTaskId());
        }

        var preparedRequest = executableRequestBuilder.build(testCase, apiSpec, request);
        if (preparedRequest.blocked()) {
            var message = preparedRequest.message();
            var record = persistExecutionRecord(
                request,
                testCase,
                preparedRequest.requestSnapshot(),
                responseSnapshotFactory.blocked(message),
                OverallStatus.BLOCKED,
                List.of(),
                0L,
                null,
                message
            );
            updateTaskCaseExecution(request, testCase, record, HttpExecutionOutcomeStatus.BLOCKED);
            return singleCaseResult(
                task.getTaskId(),
                request,
                testCase.getCaseId(),
                record,
                HttpExecutionOutcomeStatus.BLOCKED,
                0L,
                null,
                message,
                preparedRequest.requestSnapshot()
            );
        }
        if (request.dryRun()) {
            var message = "Dry run prepared request; transport not called";
            var record = persistExecutionRecord(
                request,
                testCase,
                preparedRequest.requestSnapshot(),
                responseSnapshotFactory.dryRun(message),
                OverallStatus.SKIPPED,
                List.of(),
                0L,
                null,
                null
            );
            updateTaskCaseExecution(request, testCase, record, HttpExecutionOutcomeStatus.SKIPPED);
            return singleCaseResult(
                task.getTaskId(),
                request,
                testCase.getCaseId(),
                record,
                HttpExecutionOutcomeStatus.SKIPPED,
                0L,
                null,
                message,
                preparedRequest.requestSnapshot()
            );
        }

        var httpRequest = preparedRequest.clientRequest();
        var startedAt = System.nanoTime();
        try {
            var httpResponse = httpClientGateway.execute(httpRequest, request.options());
            var durationMs = normalizedDuration(httpResponse.durationMs(), startedAt);
            var assertionResults = assertionChecker.check(testCase, httpResponse, durationMs);
            var overallStatus = overallStatusForResponse(httpResponse.statusCode(), assertionResults);
            var outcomeStatus = outcomeStatusFor(overallStatus);
            var record = persistExecutionRecord(
                request,
                testCase,
                preparedRequest.requestSnapshot(),
                responseSnapshotFactory.success(httpResponse, durationMs),
                overallStatus,
                assertionResults,
                durationMs,
                httpResponse.statusCode(),
                null
            );
            updateTaskCaseExecution(request, testCase, record, outcomeStatus);
            return singleCaseResult(
                task.getTaskId(),
                request,
                testCase.getCaseId(),
                record,
                outcomeStatus,
                durationMs,
                httpResponse.statusCode(),
                null,
                preparedRequest.requestSnapshot()
            );
        } catch (HttpTransportException exception) {
            var record = persistExecutionRecord(
                request,
                testCase,
                preparedRequest.requestSnapshot(),
                responseSnapshotFactory.transportError(exception),
                OverallStatus.ERROR,
                List.of(),
                exception.durationMs(),
                null,
                exception.getMessage()
            );
            updateTaskCaseExecution(request, testCase, record, HttpExecutionOutcomeStatus.ERROR);
            return singleCaseResult(
                task.getTaskId(),
                request,
                testCase.getCaseId(),
                record,
                HttpExecutionOutcomeStatus.ERROR,
                exception.durationMs(),
                null,
                exception.getMessage(),
                preparedRequest.requestSnapshot()
            );
        } catch (RuntimeException exception) {
            var durationMs = normalizedDuration(-1L, startedAt);
            var message = StringUtils.hasText(exception.getMessage())
                ? exception.getMessage()
                : exception.getClass().getSimpleName();
            var record = persistExecutionRecord(
                request,
                testCase,
                preparedRequest.requestSnapshot(),
                responseSnapshotFactory.transportError(message, durationMs),
                OverallStatus.ERROR,
                List.of(),
                durationMs,
                null,
                message
            );
            updateTaskCaseExecution(request, testCase, record, HttpExecutionOutcomeStatus.ERROR);
            return singleCaseResult(
                task.getTaskId(),
                request,
                testCase.getCaseId(),
                record,
                HttpExecutionOutcomeStatus.ERROR,
                durationMs,
                null,
                message,
                preparedRequest.requestSnapshot()
            );
        }
    }

    private void validateRequest(HttpExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("HTTP execution request is required");
        }
        if (!StringUtils.hasText(request.taskId())) {
            throw new IllegalArgumentException("Task id is required");
        }
        if (request.selectedCaseIds() == null || request.selectedCaseIds().isEmpty()) {
            throw new IllegalArgumentException("At least one selected case id is required");
        }
        if (request.executionMode() == null) {
            throw new IllegalArgumentException("Execution mode is required");
        }
    }

    private ExecutionRecord persistExecutionRecord(
        HttpExecutionRequest request,
        TestCase testCase,
        Map<String, Object> requestSnapshot,
        Map<String, Object> responseSnapshot,
        OverallStatus overallStatus,
        List<Map<String, Object>> assertionResults,
        long durationMs,
        Integer statusCode,
        String errorMessage
    ) {
        var record = new ExecutionRecord();
        record.setTaskId(request.taskId());
        record.setCaseId(testCase.getCaseId());
        record.setExecutorType(ExecutorType.HTTP);
        record.setEnvironment(request.environment());
        record.setRequestSnapshot(requestSnapshot);
        record.setResponseSnapshot(responseSnapshot);
        record.setAssertionResults(assertionResults);
        record.setOverallStatus(overallStatus);
        record.setCriticalFailed(overallStatus == OverallStatus.FAILED
            || overallStatus == OverallStatus.ERROR
            || overallStatus == OverallStatus.BLOCKED);
        record.setDurationMs(durationMs);
        record.setStatusCode(statusCode);
        record.setErrorMessage(errorMessage);
        return executionRecords.save(record);
    }

    private void updateTaskCaseExecution(
        HttpExecutionRequest request,
        TestCase testCase,
        ExecutionRecord record,
        HttpExecutionOutcomeStatus outcomeStatus
    ) {
        var taskCaseExecution = taskCaseExecutions.findFirstByTaskIdAndCaseId(request.taskId(), testCase.getCaseId())
            .orElseGet(TaskCaseExecution::new);
        taskCaseExecution.setTaskId(request.taskId());
        taskCaseExecution.setCaseId(testCase.getCaseId());
        taskCaseExecution.setExecutionMode(request.executionMode());
        taskCaseExecution.setExecutionStatus(taskCaseExecutionStatusFor(outcomeStatus));
        taskCaseExecution.setExecutionRecordId(record.getExecutionId());
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("environment", request.environment());
        snapshot.put("status", outcomeStatus.name());
        snapshot.put("durationMs", record.getDurationMs());
        snapshot.put("statusCode", record.getStatusCode());
        snapshot.put("executionRecordId", record.getExecutionId());
        taskCaseExecution.setSnapshotJson(snapshot);
        taskCaseExecutions.save(taskCaseExecution);
    }

    private HttpExecutionResult singleCaseResult(
        String taskId,
        HttpExecutionRequest request,
        String caseId,
        ExecutionRecord record,
        HttpExecutionOutcomeStatus status,
        long durationMs,
        Integer statusCode,
        String message,
        Map<String, Object> requestSnapshot
    ) {
        var caseResult = new HttpExecutionCaseResult(
            caseId,
            record.getExecutionId(),
            status,
            durationMs,
            statusCode,
            message,
            requestSnapshot
        );
        var caseResults = List.of(caseResult);
        return new HttpExecutionResult(
            taskId,
            request.environment(),
            request.executionMode(),
            caseResults,
            HttpExecutionCounts.from(caseResults)
        );
    }

    private HttpExecutionOutcomeStatus statusFor(int statusCode) {
        return statusCode >= 200 && statusCode < 400
            ? HttpExecutionOutcomeStatus.PASSED
            : HttpExecutionOutcomeStatus.FAILED;
    }

    private OverallStatus overallStatusForResponse(int statusCode, List<Map<String, Object>> assertionResults) {
        if (assertionResults.isEmpty()) {
            return statusFor(statusCode) == HttpExecutionOutcomeStatus.PASSED
                ? OverallStatus.PASSED
                : OverallStatus.FAILED;
        }
        if (assertionResults.stream().anyMatch(this::criticalAssertionFailed)) {
            return OverallStatus.FAILED;
        }
        if (assertionResults.stream().anyMatch(this::assertionFailed)) {
            return OverallStatus.PASSED_WITH_WARNINGS;
        }
        return OverallStatus.PASSED;
    }

    private HttpExecutionOutcomeStatus outcomeStatusFor(OverallStatus overallStatus) {
        return switch (overallStatus) {
            case PASSED, PASSED_WITH_WARNINGS -> HttpExecutionOutcomeStatus.PASSED;
            case FAILED -> HttpExecutionOutcomeStatus.FAILED;
            case ERROR -> HttpExecutionOutcomeStatus.ERROR;
            case SKIPPED -> HttpExecutionOutcomeStatus.SKIPPED;
            case BLOCKED -> HttpExecutionOutcomeStatus.BLOCKED;
        };
    }

    private boolean criticalAssertionFailed(Map<String, Object> assertionResult) {
        return assertionFailed(assertionResult) && Boolean.TRUE.equals(assertionResult.get("critical"));
    }

    private boolean assertionFailed(Map<String, Object> assertionResult) {
        return !"PASSED".equals(assertionResult.get("status"));
    }

    private TaskCaseExecutionStatus taskCaseExecutionStatusFor(HttpExecutionOutcomeStatus outcomeStatus) {
        return switch (outcomeStatus) {
            case PASSED -> TaskCaseExecutionStatus.COMPLETED;
            case FAILED -> TaskCaseExecutionStatus.FAILED;
            case ERROR -> TaskCaseExecutionStatus.ERROR;
            case SKIPPED -> TaskCaseExecutionStatus.SKIPPED;
            case BLOCKED -> TaskCaseExecutionStatus.BLOCKED;
        };
    }

    private long normalizedDuration(long reportedDurationMs, long startedAt) {
        if (reportedDurationMs >= 0) {
            return reportedDurationMs;
        }
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000);
    }
}
