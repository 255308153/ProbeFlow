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

    public HttpExecutionApplicationService(
        TaskRepository tasks,
        TestCaseRepository testCases,
        ApiSpecRepository apiSpecs,
        ExecutionRecordRepository executionRecords,
        TaskCaseExecutionRepository taskCaseExecutions,
        HttpClientGateway httpClientGateway
    ) {
        this.tasks = tasks;
        this.testCases = testCases;
        this.apiSpecs = apiSpecs;
        this.executionRecords = executionRecords;
        this.taskCaseExecutions = taskCaseExecutions;
        this.httpClientGateway = httpClientGateway;
    }

    @Transactional
    public HttpExecutionResult execute(HttpExecutionRequest request) {
        validateRequest(request);
        var task = tasks.findById(request.taskId())
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + request.taskId()));
        if (request.dryRun()) {
            throw new UnsupportedOperationException("Dry-run request preparation is implemented in Phase 6 issue 02");
        }
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

        var httpRequest = buildBasicRequest(testCase, apiSpec);
        var startedAt = System.nanoTime();
        var httpResponse = httpClientGateway.execute(httpRequest, request.options());
        var durationMs = normalizedDuration(httpResponse.durationMs(), startedAt);
        var outcomeStatus = statusFor(httpResponse.statusCode());
        var record = persistExecutionRecord(request, testCase, httpRequest, httpResponse, durationMs, outcomeStatus);
        updateTaskCaseExecution(request, testCase, record, outcomeStatus);

        var caseResult = new HttpExecutionCaseResult(
            testCase.getCaseId(),
            record.getExecutionId(),
            outcomeStatus,
            durationMs,
            httpResponse.statusCode(),
            null
        );
        var caseResults = List.of(caseResult);
        return new HttpExecutionResult(
            task.getTaskId(),
            request.environment(),
            request.executionMode(),
            caseResults,
            HttpExecutionCounts.from(caseResults)
        );
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

    private HttpClientRequest buildBasicRequest(TestCase testCase, ApiSpec apiSpec) {
        var requestShape = requestShape(testCase);
        var method = stringValue(requestShape.get("method"));
        if (!StringUtils.hasText(method)) {
            method = apiSpec.getHttpMethod().name();
        }
        var path = stringValue(requestShape.get("path"));
        if (!StringUtils.hasText(path)) {
            path = apiSpec.getPath();
        }
        return new HttpClientRequest(
            method,
            path,
            objectMap(requestShape.get("headers")),
            requestShape.get("body")
        );
    }

    private Map<String, Object> requestShape(TestCase testCase) {
        var detailShape = objectMap(testCase.getDetail().get("requestShape"));
        if (!detailShape.isEmpty()) {
            return detailShape;
        }
        if (!testCase.getSteps().isEmpty()) {
            return objectMap(testCase.getSteps().getFirst().get("requestShape"));
        }
        return Map.of();
    }

    private ExecutionRecord persistExecutionRecord(
        HttpExecutionRequest request,
        TestCase testCase,
        HttpClientRequest httpRequest,
        HttpClientResponse httpResponse,
        long durationMs,
        HttpExecutionOutcomeStatus outcomeStatus
    ) {
        var record = new ExecutionRecord();
        record.setTaskId(request.taskId());
        record.setCaseId(testCase.getCaseId());
        record.setExecutorType(ExecutorType.HTTP);
        record.setEnvironment(request.environment());
        record.setRequestSnapshot(requestSnapshot(testCase, httpRequest));
        record.setResponseSnapshot(responseSnapshot(httpResponse, durationMs));
        record.setAssertionResults(List.of());
        record.setOverallStatus(outcomeStatus == HttpExecutionOutcomeStatus.PASSED ? OverallStatus.PASSED : OverallStatus.FAILED);
        record.setCriticalFailed(outcomeStatus != HttpExecutionOutcomeStatus.PASSED);
        record.setDurationMs(durationMs);
        record.setStatusCode(httpResponse.statusCode());
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
        taskCaseExecution.setExecutionStatus(outcomeStatus == HttpExecutionOutcomeStatus.PASSED
            ? TaskCaseExecutionStatus.COMPLETED
            : TaskCaseExecutionStatus.EXECUTING);
        taskCaseExecution.setExecutionRecordId(record.getExecutionId());
        taskCaseExecution.setSnapshotJson(Map.of(
            "environment", request.environment(),
            "status", outcomeStatus.name(),
            "durationMs", record.getDurationMs(),
            "statusCode", record.getStatusCode(),
            "executionRecordId", record.getExecutionId()
        ));
        taskCaseExecutions.save(taskCaseExecution);
    }

    private Map<String, Object> requestSnapshot(TestCase testCase, HttpClientRequest request) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("caseId", testCase.getCaseId());
        snapshot.put("apiSpecId", testCase.getPrimaryApiSpecId());
        snapshot.put("method", request.method());
        snapshot.put("path", request.path());
        snapshot.put("headers", request.headers());
        if (request.body() != null) {
            snapshot.put("body", request.body());
        }
        return snapshot;
    }

    private Map<String, Object> responseSnapshot(HttpClientResponse response, long durationMs) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("statusCode", response.statusCode());
        snapshot.put("headers", response.headers());
        if (response.body() != null) {
            snapshot.put("body", response.body());
        }
        snapshot.put("durationMs", durationMs);
        return snapshot;
    }

    private HttpExecutionOutcomeStatus statusFor(int statusCode) {
        return statusCode >= 200 && statusCode < 400
            ? HttpExecutionOutcomeStatus.PASSED
            : HttpExecutionOutcomeStatus.FAILED;
    }

    private long normalizedDuration(long reportedDurationMs, long startedAt) {
        if (reportedDurationMs >= 0) {
            return reportedDurationMs;
        }
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> incoming)) {
            return Map.of();
        }
        var copied = new LinkedHashMap<String, Object>();
        incoming.forEach((key, mapValue) -> {
            if (key != null) {
                copied.put(key.toString(), mapValue);
            }
        });
        return copied;
    }
}
