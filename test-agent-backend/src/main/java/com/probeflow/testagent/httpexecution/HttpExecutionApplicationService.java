package com.probeflow.testagent.httpexecution;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.ExecutorType;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecution;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionRepository;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionStatus;
import com.probeflow.testagent.testcase.TestCase;
import com.probeflow.testagent.testcase.TestCaseMode;
import com.probeflow.testagent.testcase.TestCaseRepository;
import java.util.ArrayList;
import java.util.Comparator;
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
        if (request.executionMode() == ExecutionMode.BATCH) {
            return executeBatch(task, request);
        }
        if ((request.executionMode() != ExecutionMode.SINGLE && request.executionMode() != ExecutionMode.SUITE_STEP)
            || request.selectedCaseIds().size() != 1) {
            throw new IllegalArgumentException("Issue 01 supports exactly one selected case in SINGLE mode");
        }

        var caseResult = executeSelectedCase(task, request, request.selectedCaseIds().getFirst(), true);
        return executionResult(task.getTaskId(), request, List.of(caseResult));
    }

    private HttpExecutionResult executeBatch(Task task, HttpExecutionRequest request) {
        var caseResults = new ArrayList<HttpExecutionCaseResult>();
        var halted = false;
        var skipReason = "";
        for (var caseId : request.selectedCaseIds()) {
            if (halted) {
                caseResults.add(skipSelectedCase(request, caseId, skipReason));
                continue;
            }

            var caseResult = executeSelectedCase(task, request, caseId, false);
            caseResults.add(caseResult);
            if (shouldHaltBatch(request, caseResult)) {
                halted = true;
                skipReason = haltReason(request, caseResult);
            }
        }
        return executionResult(task.getTaskId(), request, caseResults);
    }

    private HttpExecutionCaseResult executeSelectedCase(
        Task task,
        HttpExecutionRequest request,
        String caseId,
        boolean strictReferences
    ) {
        var testCase = testCases.findById(caseId).orElse(null);
        if (testCase == null) {
            if (strictReferences) {
                throw new IllegalArgumentException("TestCase not found: " + caseId);
            }
            return selectionErrorCaseResult(request, caseId, "TestCase not found: " + caseId);
        }
        if (testCase.getMode() == TestCaseMode.SUITE) {
            return executeSuiteCase(task, request, testCase);
        }
        if (testCase.getMode() != TestCaseMode.SINGLE) {
            var message = "Batch execution supports only SINGLE TestCase before SUITE support: " + caseId;
            if (strictReferences) {
                throw new IllegalArgumentException(message);
            }
            return blockedCaseResult(request, testCase, message, minimalRequestSnapshot(testCase.getCaseId()));
        }

        var apiSpec = apiSpecs.findById(testCase.getPrimaryApiSpecId()).orElse(null);
        if (apiSpec == null) {
            var message = "ApiSpec not found: " + testCase.getPrimaryApiSpecId();
            if (strictReferences) {
                throw new IllegalArgumentException(message);
            }
            return selectionErrorCaseResult(request, testCase.getCaseId(), message);
        }
        if (!task.getTargetApiSpecIds().isEmpty() && !task.getTargetApiSpecIds().contains(apiSpec.getApiSpecId())) {
            var message = "TestCase " + caseId + " is not selected by Task " + task.getTaskId();
            if (strictReferences) {
                throw new IllegalArgumentException(message);
            }
            return blockedCaseResult(request, testCase, message, minimalRequestSnapshot(testCase.getCaseId()));
        }

        var preparedRequest = executableRequestBuilder.build(testCase, apiSpec, request);
        if (preparedRequest.blocked()) {
            var message = preparedRequest.message();
            return blockedCaseResult(request, testCase, message, preparedRequest.requestSnapshot());
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
            return caseResult(
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
            var responseSnapshot = responseSnapshotFactory.success(httpResponse, durationMs);
            classifyAssertionFailure(responseSnapshot, overallStatus, assertionResults);
            var record = persistExecutionRecord(
                request,
                testCase,
                preparedRequest.requestSnapshot(),
                responseSnapshot,
                overallStatus,
                assertionResults,
                durationMs,
                httpResponse.statusCode(),
                null
            );
            updateTaskCaseExecution(request, testCase, record, outcomeStatus);
            return caseResult(
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
            return caseResult(
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
            return caseResult(
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

    private HttpExecutionCaseResult executeSuiteCase(Task task, HttpExecutionRequest request, TestCase testCase) {
        var stepResults = new ArrayList<Map<String, Object>>();
        var flattenedAssertions = new ArrayList<Map<String, Object>>();
        var totalDurationMs = 0L;
        Integer lastStatusCode = null;
        var halted = false;
        var skipReason = "";

        for (var step : orderedSteps(testCase)) {
            if (halted) {
                stepResults.add(skippedStepResult(step, skipReason));
                continue;
            }

            var stepResult = executeSuiteStep(task, request, testCase, step);
            stepResults.add(stepResult);
            totalDurationMs += longValue(stepResult.get("durationMs"));
            if (stepResult.get("statusCode") instanceof Number statusCode) {
                lastStatusCode = statusCode.intValue();
            }
            flattenedAssertions.addAll(stepAssertionsWithStepRefs(stepResult));
            if (request.options().stopOnCriticalFailure() && stepCriticalFailure(stepResult)) {
                halted = true;
                skipReason = "Skipped because prerequisite step failed: " + stepId(step);
            }
        }

        var overallStatus = suiteOverallStatus(stepResults);
        var outcomeStatus = outcomeStatusFor(overallStatus);
        var record = persistExecutionRecord(
            request,
            testCase,
            suiteRequestSnapshot(testCase),
            suiteResponseSnapshot(stepResults),
            overallStatus,
            flattenedAssertions,
            totalDurationMs,
            lastStatusCode,
            firstStepMessage(stepResults)
        );
        updateTaskCaseExecution(request, testCase, record, outcomeStatus);
        return caseResult(
            testCase.getCaseId(),
            record,
            outcomeStatus,
            totalDurationMs,
            lastStatusCode,
            firstStepMessage(stepResults),
            suiteRequestSnapshot(testCase)
        );
    }

    private Map<String, Object> executeSuiteStep(
        Task task,
        HttpExecutionRequest request,
        TestCase testCase,
        Map<String, Object> step
    ) {
        var apiSpecId = stringValue(firstPresent(step, "apiSpecId", "targetApiSpecId"));
        if (!StringUtils.hasText(apiSpecId)) {
            apiSpecId = testCase.getPrimaryApiSpecId();
        }
        var apiSpec = apiSpecs.findById(apiSpecId).orElse(null);
        if (apiSpec == null) {
            return terminalStepResult(step, HttpExecutionOutcomeStatus.ERROR, OverallStatus.ERROR,
                "ApiSpec not found: " + apiSpecId, minimalRequestSnapshot(testCase.getCaseId()), Map.of(), List.of(), 0L, null);
        }
        if (!task.getTargetApiSpecIds().isEmpty() && !task.getTargetApiSpecIds().contains(apiSpec.getApiSpecId())) {
            var message = "Suite step " + stepId(step) + " is not selected by Task " + task.getTaskId();
            return terminalStepResult(step, HttpExecutionOutcomeStatus.BLOCKED, OverallStatus.BLOCKED,
                message, minimalRequestSnapshot(testCase.getCaseId()), responseSnapshotFactory.blocked(message), List.of(), 0L, null);
        }

        var preparedRequest = executableRequestBuilder.buildStep(testCase, step, apiSpec, request);
        if (preparedRequest.blocked()) {
            var message = preparedRequest.message();
            return terminalStepResult(step, HttpExecutionOutcomeStatus.BLOCKED, OverallStatus.BLOCKED,
                message, preparedRequest.requestSnapshot(), responseSnapshotFactory.blocked(message), List.of(), 0L, null);
        }
        if (request.dryRun()) {
            var message = "Dry run prepared request; transport not called";
            return terminalStepResult(step, HttpExecutionOutcomeStatus.SKIPPED, OverallStatus.SKIPPED,
                message, preparedRequest.requestSnapshot(), responseSnapshotFactory.dryRun(message), List.of(), 0L, null);
        }

        var startedAt = System.nanoTime();
        try {
            var httpResponse = httpClientGateway.execute(preparedRequest.clientRequest(), request.options());
            var durationMs = normalizedDuration(httpResponse.durationMs(), startedAt);
            var assertionResults = assertionChecker.checkStep(step, httpResponse, durationMs);
            var overallStatus = overallStatusForResponse(httpResponse.statusCode(), assertionResults);
            var responseSnapshot = responseSnapshotFactory.success(httpResponse, durationMs);
            classifyAssertionFailure(responseSnapshot, overallStatus, assertionResults);
            return terminalStepResult(
                step,
                outcomeStatusFor(overallStatus),
                overallStatus,
                null,
                preparedRequest.requestSnapshot(),
                responseSnapshot,
                assertionResults,
                durationMs,
                httpResponse.statusCode()
            );
        } catch (HttpTransportException exception) {
            return terminalStepResult(step, HttpExecutionOutcomeStatus.ERROR, OverallStatus.ERROR,
                exception.getMessage(), preparedRequest.requestSnapshot(), responseSnapshotFactory.transportError(exception),
                List.of(), exception.durationMs(), null);
        } catch (RuntimeException exception) {
            var durationMs = normalizedDuration(-1L, startedAt);
            var message = StringUtils.hasText(exception.getMessage())
                ? exception.getMessage()
                : exception.getClass().getSimpleName();
            return terminalStepResult(step, HttpExecutionOutcomeStatus.ERROR, OverallStatus.ERROR,
                message, preparedRequest.requestSnapshot(), responseSnapshotFactory.transportError(message, durationMs),
                List.of(), durationMs, null);
        }
    }

    private List<Map<String, Object>> orderedSteps(TestCase testCase) {
        var steps = new ArrayList<>(testCase.getSteps());
        steps.sort(Comparator.comparingInt(this::stepOrder));
        return steps;
    }

    private Map<String, Object> skippedStepResult(Map<String, Object> step, String reason) {
        return terminalStepResult(
            step,
            HttpExecutionOutcomeStatus.SKIPPED,
            OverallStatus.SKIPPED,
            reason,
            Map.of("stepId", stepId(step), "stepOrder", stepOrder(step)),
            responseSnapshotFactory.skipped(reason),
            List.of(),
            0L,
            null
        );
    }

    private Map<String, Object> terminalStepResult(
        Map<String, Object> step,
        HttpExecutionOutcomeStatus status,
        OverallStatus overallStatus,
        String message,
        Map<String, Object> requestSnapshot,
        Map<String, Object> responseSnapshot,
        List<Map<String, Object>> assertionResults,
        long durationMs,
        Integer statusCode
    ) {
        var result = new LinkedHashMap<String, Object>();
        result.put("stepId", stepId(step));
        result.put("order", stepOrder(step));
        result.put("apiSpecId", firstPresent(step, "apiSpecId", "targetApiSpecId"));
        result.put("status", status.name());
        result.put("overallStatus", overallStatus.name());
        result.put("criticalFailed", overallStatus == OverallStatus.FAILED
            || overallStatus == OverallStatus.ERROR
            || overallStatus == OverallStatus.BLOCKED);
        result.put("durationMs", durationMs);
        result.put("statusCode", statusCode);
        if (StringUtils.hasText(message)) {
            result.put("message", message);
        }
        result.put("requestSnapshot", requestSnapshot);
        result.put("responseSnapshot", responseSnapshot);
        result.put("assertionResults", assertionResults);
        return result;
    }

    private List<Map<String, Object>> stepAssertionsWithStepRefs(Map<String, Object> stepResult) {
        @SuppressWarnings("unchecked")
        var assertions = (List<Map<String, Object>>) stepResult.getOrDefault("assertionResults", List.of());
        var enriched = new ArrayList<Map<String, Object>>();
        for (var assertion : assertions) {
            var copy = new LinkedHashMap<String, Object>(assertion);
            copy.put("stepId", stepResult.get("stepId"));
            copy.put("stepOrder", stepResult.get("order"));
            copy.put("apiSpecId", stepResult.get("apiSpecId"));
            enriched.add(copy);
        }
        return enriched;
    }

    private boolean stepCriticalFailure(Map<String, Object> stepResult) {
        return Boolean.TRUE.equals(stepResult.get("criticalFailed"));
    }

    private OverallStatus suiteOverallStatus(List<Map<String, Object>> stepResults) {
        if (hasStepOverall(stepResults, OverallStatus.ERROR)) {
            return OverallStatus.ERROR;
        }
        if (hasStepOverall(stepResults, OverallStatus.BLOCKED)) {
            return OverallStatus.BLOCKED;
        }
        if (hasStepOverall(stepResults, OverallStatus.FAILED)) {
            return OverallStatus.FAILED;
        }
        if (hasStepOverall(stepResults, OverallStatus.PASSED_WITH_WARNINGS)) {
            return OverallStatus.PASSED_WITH_WARNINGS;
        }
        if (!stepResults.isEmpty() && stepResults.stream().allMatch(step -> OverallStatus.SKIPPED.name().equals(step.get("overallStatus")))) {
            return OverallStatus.SKIPPED;
        }
        return OverallStatus.PASSED;
    }

    private boolean hasStepOverall(List<Map<String, Object>> stepResults, OverallStatus status) {
        return stepResults.stream().anyMatch(step -> status.name().equals(step.get("overallStatus")));
    }

    private Map<String, Object> suiteRequestSnapshot(TestCase testCase) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("caseId", testCase.getCaseId());
        snapshot.put("suite", true);
        snapshot.put("stepCount", testCase.getSteps().size());
        snapshot.put("steps", orderedSteps(testCase).stream()
            .map(step -> {
                var ref = new LinkedHashMap<String, Object>();
                ref.put("stepId", stepId(step));
                ref.put("order", stepOrder(step));
                ref.put("apiSpecId", firstPresent(step, "apiSpecId", "targetApiSpecId"));
                return ref;
            })
            .toList());
        return snapshot;
    }

    private Map<String, Object> suiteResponseSnapshot(List<Map<String, Object>> stepResults) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("suite", true);
        snapshot.put("steps", stepResults);
        return snapshot;
    }

    private String firstStepMessage(List<Map<String, Object>> stepResults) {
        return stepResults.stream()
            .map(step -> step.get("message"))
            .filter(message -> message != null && StringUtils.hasText(message.toString()))
            .map(Object::toString)
            .findFirst()
            .orElse(null);
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        for (var key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private int stepOrder(Map<String, Object> step) {
        var value = step.get("order");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && StringUtils.hasText(value.toString())) {
            return Integer.parseInt(value.toString());
        }
        return Integer.MAX_VALUE;
    }

    private String stepId(Map<String, Object> step) {
        var value = firstPresent(step, "stepId", "stepName");
        return value == null ? "step-" + stepOrder(step) : value.toString();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(value.toString());
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
        return persistExecutionRecord(
            request,
            testCase.getCaseId(),
            requestSnapshot,
            responseSnapshot,
            overallStatus,
            assertionResults,
            durationMs,
            statusCode,
            errorMessage
        );
    }

    private ExecutionRecord persistExecutionRecord(
        HttpExecutionRequest request,
        String caseId,
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
        record.setCaseId(caseId);
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
        updateTaskCaseExecution(request, testCase.getCaseId(), record, outcomeStatus);
    }

    private void updateTaskCaseExecution(
        HttpExecutionRequest request,
        String caseId,
        ExecutionRecord record,
        HttpExecutionOutcomeStatus outcomeStatus
    ) {
        var taskCaseExecution = taskCaseExecutions.findFirstByTaskIdAndCaseId(request.taskId(), caseId)
            .orElseGet(TaskCaseExecution::new);
        taskCaseExecution.setTaskId(request.taskId());
        taskCaseExecution.setCaseId(caseId);
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

    private HttpExecutionCaseResult blockedCaseResult(
        HttpExecutionRequest request,
        TestCase testCase,
        String message,
        Map<String, Object> requestSnapshot
    ) {
        var record = persistExecutionRecord(
            request,
            testCase,
            requestSnapshot,
            responseSnapshotFactory.blocked(message),
            OverallStatus.BLOCKED,
            List.of(),
            0L,
            null,
            message
        );
        updateTaskCaseExecution(request, testCase, record, HttpExecutionOutcomeStatus.BLOCKED);
        return caseResult(
            testCase.getCaseId(),
            record,
            HttpExecutionOutcomeStatus.BLOCKED,
            0L,
            null,
            message,
            requestSnapshot
        );
    }

    private HttpExecutionCaseResult selectionErrorCaseResult(
        HttpExecutionRequest request,
        String caseId,
        String message
    ) {
        var requestSnapshot = minimalRequestSnapshot(caseId);
        var record = persistExecutionRecord(
            request,
            caseId,
            requestSnapshot,
            responseSnapshotFactory.error("SELECTION_ERROR", message, 0L),
            OverallStatus.ERROR,
            List.of(),
            0L,
            null,
            message
        );
        updateTaskCaseExecution(request, caseId, record, HttpExecutionOutcomeStatus.ERROR);
        return caseResult(
            caseId,
            record,
            HttpExecutionOutcomeStatus.ERROR,
            0L,
            null,
            message,
            requestSnapshot
        );
    }

    private HttpExecutionCaseResult skipSelectedCase(
        HttpExecutionRequest request,
        String caseId,
        String reason
    ) {
        var testCase = testCases.findById(caseId).orElse(null);
        var requestSnapshot = minimalRequestSnapshot(caseId);
        var record = persistExecutionRecord(
            request,
            caseId,
            requestSnapshot,
            responseSnapshotFactory.skipped(reason),
            OverallStatus.SKIPPED,
            List.of(),
            0L,
            null,
            null
        );
        if (testCase == null) {
            updateTaskCaseExecution(request, caseId, record, HttpExecutionOutcomeStatus.SKIPPED);
        } else {
            updateTaskCaseExecution(request, testCase, record, HttpExecutionOutcomeStatus.SKIPPED);
        }
        return caseResult(
            caseId,
            record,
            HttpExecutionOutcomeStatus.SKIPPED,
            0L,
            null,
            reason,
            requestSnapshot
        );
    }

    private HttpExecutionCaseResult caseResult(
        String caseId,
        ExecutionRecord record,
        HttpExecutionOutcomeStatus status,
        long durationMs,
        Integer statusCode,
        String message,
        Map<String, Object> requestSnapshot
    ) {
        return new HttpExecutionCaseResult(
            caseId,
            record.getExecutionId(),
            status,
            durationMs,
            statusCode,
            message,
            requestSnapshot
        );
    }

    private HttpExecutionResult executionResult(
        String taskId,
        HttpExecutionRequest request,
        List<HttpExecutionCaseResult> caseResults
    ) {
        return new HttpExecutionResult(
            taskId,
            request.environment(),
            request.executionMode(),
            caseResults,
            HttpExecutionCounts.from(caseResults)
        );
    }

    private boolean shouldHaltBatch(HttpExecutionRequest request, HttpExecutionCaseResult caseResult) {
        if (request.options().stopOnCriticalFailure() && criticalFailure(caseResult)) {
            return true;
        }
        return !request.options().continueOnFailure() && caseResult.status() != HttpExecutionOutcomeStatus.PASSED;
    }

    private String haltReason(HttpExecutionRequest request, HttpExecutionCaseResult caseResult) {
        if (request.options().stopOnCriticalFailure() && criticalFailure(caseResult)) {
            return "Skipped because a prior critical failure stopped the batch";
        }
        return "Skipped because continue-on-failure is disabled";
    }

    private boolean criticalFailure(HttpExecutionCaseResult caseResult) {
        if (caseResult.executionRecordId() == null) {
            return caseResult.status() == HttpExecutionOutcomeStatus.FAILED
                || caseResult.status() == HttpExecutionOutcomeStatus.ERROR
                || caseResult.status() == HttpExecutionOutcomeStatus.BLOCKED;
        }
        return executionRecords.findById(caseResult.executionRecordId())
            .map(ExecutionRecord::isCriticalFailed)
            .orElse(false);
    }

    private Map<String, Object> minimalRequestSnapshot(String caseId) {
        return Map.of("caseId", caseId);
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

    private void classifyAssertionFailure(
        Map<String, Object> responseSnapshot,
        OverallStatus overallStatus,
        List<Map<String, Object>> assertionResults
    ) {
        if (overallStatus == OverallStatus.FAILED && !assertionResults.isEmpty()) {
            responseSnapshot.put("failureType", "ASSERTION_FAILURE");
        }
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
