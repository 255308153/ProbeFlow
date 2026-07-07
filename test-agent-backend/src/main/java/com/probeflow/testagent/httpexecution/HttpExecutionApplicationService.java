package com.probeflow.testagent.httpexecution;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.ExecutorType;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.memory.TaskMemoryService;
import com.probeflow.testagent.memory.TaskMemoryWriteRequest;
import com.probeflow.testagent.suiteruntime.ExecutionContext;
import com.probeflow.testagent.suiteruntime.ResponseExtractor;
import com.probeflow.testagent.suiteruntime.RuntimeRedactor;
import com.probeflow.testagent.suiteruntime.VariableResolver;
import com.probeflow.testagent.suiteruntime.VariableWriteBackService;
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
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<TaskMemoryService> taskMemoryServiceProvider;
    private final VariableResolver variableResolver;
    private final ResponseExtractor responseExtractor;
    private final VariableWriteBackService variableWriteBackService;

    public HttpExecutionApplicationService(
        TaskRepository tasks,
        TestCaseRepository testCases,
        ApiSpecRepository apiSpecs,
        ExecutionRecordRepository executionRecords,
        TaskCaseExecutionRepository taskCaseExecutions,
        HttpClientGateway httpClientGateway,
        ExecutableRequestBuilder executableRequestBuilder,
        HttpResponseSnapshotFactory responseSnapshotFactory,
        BaselineHttpAssertionChecker assertionChecker,
        ObjectProvider<TaskMemoryService> taskMemoryServiceProvider,
        VariableResolver variableResolver,
        ResponseExtractor responseExtractor,
        VariableWriteBackService variableWriteBackService
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
        this.taskMemoryServiceProvider = taskMemoryServiceProvider;
        this.variableResolver = variableResolver;
        this.responseExtractor = responseExtractor;
        this.variableWriteBackService = variableWriteBackService;
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
        var executionContext = ExecutionContext.create(task, testCase, request);
        var stepResults = new ArrayList<Map<String, Object>>();
        var flattenedAssertions = new ArrayList<Map<String, Object>>();
        var totalDurationMs = 0L;
        Integer lastStatusCode = null;
        var halted = false;
        var skipReason = "";

        for (var step : orderedSteps(testCase)) {
            if (halted) {
                stepResults.add(withStepRuntime(skippedStepResult(step, skipReason), executionContext, stepId(step), executionContext.summary()));
                continue;
            }

            var stepResult = executeSuiteStep(task, request, testCase, step, executionContext);
            stepResults.add(stepResult);
            totalDurationMs += longValue(stepResult.get("durationMs"));
            if (stepResult.get("statusCode") instanceof Number statusCode) {
                lastStatusCode = statusCode.intValue();
            }
            flattenedAssertions.addAll(stepAssertionsWithStepRefs(stepResult));
            if (Boolean.TRUE.equals(stepResult.get("runtimeBlockingFailure"))) {
                halted = true;
                skipReason = "Skipped because prerequisite runtime variable failure in step: " + stepId(step);
            } else if (request.options().stopOnCriticalFailure() && stepCriticalFailure(stepResult)) {
                halted = true;
                skipReason = "Skipped because prerequisite step failed: " + stepId(step);
            }
        }

        var overallStatus = suiteOverallStatus(stepResults);
        var outcomeStatus = outcomeStatusFor(overallStatus);
        var suiteRequestSnapshot = suiteRequestSnapshot(testCase, executionContext);
        var record = persistExecutionRecord(
            request,
            testCase,
            suiteRequestSnapshot,
            suiteResponseSnapshot(stepResults, executionContext),
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
            suiteRequestSnapshot
        );
    }

    private Map<String, Object> executeSuiteStep(
        Task task,
        HttpExecutionRequest request,
        TestCase testCase,
        Map<String, Object> step,
        ExecutionContext executionContext
    ) {
        var currentStepId = stepId(step);
        var contextBefore = executionContext.summary();
        var apiSpecId = stringValue(firstPresent(step, "apiSpecId", "targetApiSpecId"));
        if (!StringUtils.hasText(apiSpecId)) {
            apiSpecId = testCase.getPrimaryApiSpecId();
        }
        var apiSpec = apiSpecs.findById(apiSpecId).orElse(null);
        if (apiSpec == null) {
            return withStepRuntime(terminalStepResult(step, HttpExecutionOutcomeStatus.ERROR, OverallStatus.ERROR,
                "ApiSpec not found: " + apiSpecId, minimalRequestSnapshot(testCase.getCaseId()), Map.of(), List.of(), 0L, null),
                executionContext, currentStepId, contextBefore);
        }
        if (!task.getTargetApiSpecIds().isEmpty() && !task.getTargetApiSpecIds().contains(apiSpec.getApiSpecId())) {
            var message = "Suite step " + currentStepId + " is not selected by Task " + task.getTaskId();
            return withStepRuntime(terminalStepResult(step, HttpExecutionOutcomeStatus.BLOCKED, OverallStatus.BLOCKED,
                message, minimalRequestSnapshot(testCase.getCaseId()), responseSnapshotFactory.blocked(message), List.of(), 0L, null),
                executionContext, currentStepId, contextBefore);
        }

        var requestTemplate = executableRequestBuilder.requestShapeFor(testCase, step);
        var resolvedTemplate = variableResolver.resolveRequestTemplate(executionContext, currentStepId, requestTemplate);
        if (!resolvedTemplate.successful()) {
            var message = "Variable resolution failed for suite step " + currentStepId;
            var requestSnapshot = runtimeBlockedRequestSnapshot(testCase, step, requestTemplate, contextBefore);
            var result = terminalStepResult(step, HttpExecutionOutcomeStatus.BLOCKED, OverallStatus.BLOCKED,
                message, requestSnapshot, responseSnapshotFactory.blocked(message), List.of(), 0L, null);
            result.put("runtimeBlockingFailure", true);
            return withStepRuntime(result, executionContext, currentStepId, contextBefore);
        }

        var resolvedStep = new LinkedHashMap<>(step);
        resolvedStep.put("requestShape", objectMap(resolvedTemplate.resolvedValue()));
        var preparedRequest = executableRequestBuilder.buildStep(testCase, resolvedStep, apiSpec, request);
        if (preparedRequest.blocked()) {
            var message = preparedRequest.message();
            return withStepRuntime(terminalStepResult(step, HttpExecutionOutcomeStatus.BLOCKED, OverallStatus.BLOCKED,
                message, withRequestRuntime(preparedRequest.requestSnapshot(), contextBefore), responseSnapshotFactory.blocked(message),
                List.of(), 0L, null), executionContext, currentStepId, contextBefore);
        }
        if (request.dryRun()) {
            var message = "Dry run prepared request; transport not called";
            return withStepRuntime(terminalStepResult(step, HttpExecutionOutcomeStatus.SKIPPED, OverallStatus.SKIPPED,
                message, withRequestRuntime(preparedRequest.requestSnapshot(), contextBefore), responseSnapshotFactory.dryRun(message),
                List.of(), 0L, null), executionContext, currentStepId, contextBefore);
        }

        var startedAt = System.nanoTime();
        try {
            var httpResponse = httpClientGateway.execute(preparedRequest.clientRequest(), request.options());
            var durationMs = normalizedDuration(httpResponse.durationMs(), startedAt);
            var assertionResults = assertionChecker.checkStep(step, httpResponse, durationMs);
            var overallStatus = overallStatusForResponse(httpResponse.statusCode(), assertionResults);
            var responseSnapshot = responseSnapshotFactory.success(httpResponse, durationMs);
            classifyAssertionFailure(responseSnapshot, overallStatus, assertionResults);
            var extractedVariables = responseExtractor.extract(currentStepId, step, httpResponse);
            var writeBackBlockingFailure = variableWriteBackService.write(executionContext, currentStepId, extractedVariables);
            if (writeBackBlockingFailure) {
                overallStatus = OverallStatus.BLOCKED;
                responseSnapshot.put("runtimeFailure", "VARIABLE_EXTRACTION_FAILED");
            }
            var message = writeBackBlockingFailure ? "Required variable extraction failed for suite step " + currentStepId : null;
            responseSnapshot.put("contextAfter", executionContext.summary());
            var stepResult = terminalStepResult(
                step,
                outcomeStatusFor(overallStatus),
                overallStatus,
                message,
                withRequestRuntime(preparedRequest.requestSnapshot(), contextBefore),
                responseSnapshot,
                assertionResults,
                durationMs,
                httpResponse.statusCode()
            );
            return withStepRuntime(stepResult, executionContext, currentStepId, contextBefore, writeBackBlockingFailure);
        } catch (HttpTransportException exception) {
            return withStepRuntime(terminalStepResult(step, HttpExecutionOutcomeStatus.ERROR, OverallStatus.ERROR,
                exception.getMessage(), withRequestRuntime(preparedRequest.requestSnapshot(), contextBefore),
                responseSnapshotFactory.transportError(exception),
                List.of(), exception.durationMs(), null), executionContext, currentStepId, contextBefore);
        } catch (RuntimeException exception) {
            var durationMs = normalizedDuration(-1L, startedAt);
            var message = StringUtils.hasText(exception.getMessage())
                ? exception.getMessage()
                : exception.getClass().getSimpleName();
            return withStepRuntime(terminalStepResult(step, HttpExecutionOutcomeStatus.ERROR, OverallStatus.ERROR,
                message, withRequestRuntime(preparedRequest.requestSnapshot(), contextBefore),
                responseSnapshotFactory.transportError(message, durationMs),
                List.of(), durationMs, null), executionContext, currentStepId, contextBefore);
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

    private Map<String, Object> suiteRequestSnapshot(TestCase testCase, ExecutionContext executionContext) {
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
        snapshot.put("contextSummary", executionContext.summary());
        return snapshot;
    }

    private Map<String, Object> suiteResponseSnapshot(List<Map<String, Object>> stepResults, ExecutionContext executionContext) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("suite", true);
        snapshot.put("steps", stepResults);
        snapshot.put("contextSummary", executionContext.summary());
        snapshot.put("variableAuditSummary", executionContext.variableAuditSummary());
        snapshot.put("runtimeDiagnostics", executionContext.diagnostics());
        return snapshot;
    }

    private Map<String, Object> withStepRuntime(
        Map<String, Object> stepResult,
        ExecutionContext executionContext,
        String stepId,
        Map<String, Object> contextBefore
    ) {
        return withStepRuntime(stepResult, executionContext, stepId, contextBefore, false);
    }

    private Map<String, Object> withStepRuntime(
        Map<String, Object> stepResult,
        ExecutionContext executionContext,
        String stepId,
        Map<String, Object> contextBefore,
        boolean runtimeBlockingFailure
    ) {
        stepResult.put("contextBefore", contextBefore);
        stepResult.put("contextAfter", executionContext.summary());
        stepResult.put("variableEvents", eventsForStep(executionContext, stepId));
        stepResult.put("runtimeDiagnostics", diagnosticsForStep(executionContext, stepId));
        if (runtimeBlockingFailure) {
            stepResult.put("runtimeBlockingFailure", true);
        }
        return stepResult;
    }

    private List<Map<String, Object>> eventsForStep(ExecutionContext executionContext, String stepId) {
        return executionContext.auditEvents().stream()
            .filter(event -> stepId.equals(event.get("stepId")))
            .toList();
    }

    private List<Map<String, Object>> diagnosticsForStep(ExecutionContext executionContext, String stepId) {
        return executionContext.diagnostics().stream()
            .filter(diagnostic -> stepId.equals(diagnostic.get("stepId")))
            .toList();
    }

    private Map<String, Object> withRequestRuntime(
        Map<String, Object> requestSnapshot,
        Map<String, Object> contextBefore
    ) {
        var snapshot = new LinkedHashMap<>(requestSnapshot);
        snapshot.put("contextBefore", contextBefore);
        return snapshot;
    }

    private Map<String, Object> runtimeBlockedRequestSnapshot(
        TestCase testCase,
        Map<String, Object> step,
        Map<String, Object> requestTemplate,
        Map<String, Object> contextBefore
    ) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("caseId", testCase.getCaseId());
        snapshot.put("stepId", stepId(step));
        snapshot.put("stepOrder", stepOrder(step));
        snapshot.put("apiSpecId", firstPresent(step, "apiSpecId", "targetApiSpecId"));
        snapshot.put("requestTemplate", RuntimeRedactor.redact(requestTemplate, "requestTemplate"));
        snapshot.put("contextBefore", contextBefore);
        return snapshot;
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> incoming)) {
            return new LinkedHashMap<>();
        }
        var copied = new LinkedHashMap<String, Object>();
        incoming.forEach((key, mapValue) -> {
            if (key != null) {
                copied.put(key.toString(), mapValue);
            }
        });
        return copied;
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
        writeExecutionMemory(request, caseId, record, outcomeStatus);
    }

    private void writeExecutionMemory(
        HttpExecutionRequest request,
        String caseId,
        ExecutionRecord record,
        HttpExecutionOutcomeStatus outcomeStatus
    ) {
        if (request.dryRun() || record.getOverallStatus() == OverallStatus.SKIPPED) {
            return;
        }
        var taskMemoryService = taskMemoryServiceProvider.getIfAvailable();
        if (taskMemoryService == null) {
            return;
        }

        var metadata = executionMemoryMetadata(request, caseId, record, outcomeStatus);
        taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            request.taskId(),
            executionMemoryScope(record.getOverallStatus()),
            executionMemorySummary(caseId, request.environment(), record.getOverallStatus()),
            executionMemoryContent(caseId, record, metadata),
            executionMemoryTags(request.environment(), record.getOverallStatus()),
            MemorySourceType.EXECUTION_RESULT,
            record.getExecutionId(),
            executionMemoryConfidence(record.getOverallStatus()),
            "execution",
            metadata,
            null
        ));
    }

    private MemoryScopeType executionMemoryScope(OverallStatus status) {
        return switch (status) {
            case FAILED, ERROR, BLOCKED, PASSED_WITH_WARNINGS -> MemoryScopeType.FAILURE_PATTERN;
            case PASSED -> MemoryScopeType.TESTING_PATTERN;
            case SKIPPED -> MemoryScopeType.PROJECT_KNOWLEDGE;
        };
    }

    private String executionMemorySummary(String caseId, String environment, OverallStatus status) {
        return "HTTP execution " + status.name() + " for case " + caseId + " in " + environment;
    }

    private String executionMemoryContent(
        String caseId,
        ExecutionRecord record,
        Map<String, Object> metadata
    ) {
        var parts = new ArrayList<String>();
        parts.add("Execution " + record.getExecutionId() + " for case " + caseId + " finished with status "
            + record.getOverallStatus().name() + ".");
        if (record.getStatusCode() != null) {
            parts.add("HTTP status " + record.getStatusCode() + ".");
        }
        parts.add("Duration " + record.getDurationMs() + "ms.");
        if (metadata.get("errorSummary") instanceof String errorSummary && StringUtils.hasText(errorSummary)) {
            parts.add("Error: " + errorSummary + ".");
        }
        if (metadata.get("failedAssertionSummary") instanceof String failedAssertionSummary
            && StringUtils.hasText(failedAssertionSummary)) {
            parts.add("Failed assertions: " + failedAssertionSummary + ".");
        }
        if (metadata.get("requestReference") instanceof Map<?, ?> requestReference) {
            var method = requestReference.get("method");
            var path = requestReference.get("path");
            if (method != null || path != null) {
                parts.add("Request " + stringValue(method) + " " + stringValue(path) + ".");
            }
        }
        return String.join(" ", parts);
    }

    private List<String> executionMemoryTags(String environment, OverallStatus status) {
        var tags = new ArrayList<String>();
        tags.add("http-execution");
        tags.add(status.name().toLowerCase(Locale.ROOT));
        if (StringUtils.hasText(environment)) {
            tags.add(environment);
        }
        return List.copyOf(tags);
    }

    private float executionMemoryConfidence(OverallStatus status) {
        return switch (status) {
            case ERROR, BLOCKED -> 0.9f;
            case FAILED, PASSED_WITH_WARNINGS -> 0.86f;
            case PASSED -> 0.8f;
            case SKIPPED -> 0.6f;
        };
    }

    private Map<String, Object> executionMemoryMetadata(
        HttpExecutionRequest request,
        String caseId,
        ExecutionRecord record,
        HttpExecutionOutcomeStatus outcomeStatus
    ) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("taskId", request.taskId());
        metadata.put("caseId", caseId);
        metadata.put("executionId", record.getExecutionId());
        metadata.put("environment", request.environment());
        metadata.put("status", record.getOverallStatus().name());
        metadata.put("outcomeStatus", outcomeStatus.name());
        metadata.put("durationMs", record.getDurationMs());
        if (record.getStatusCode() != null) {
            metadata.put("httpStatus", record.getStatusCode());
        }
        var errorSummary = executionErrorSummary(record);
        if (StringUtils.hasText(errorSummary)) {
            metadata.put("errorSummary", errorSummary);
        }
        var errorType = record.getResponseSnapshot().get("errorType");
        if (errorType != null) {
            metadata.put("errorType", errorType);
        }
        var failureType = record.getResponseSnapshot().get("failureType");
        if (failureType != null) {
            metadata.put("failureType", failureType);
        }
        var failedAssertions = failedAssertions(record.getAssertionResults());
        if (!failedAssertions.isEmpty()) {
            metadata.put("failedAssertions", failedAssertions);
            metadata.put("failedAssertionSummary", failedAssertionSummary(failedAssertions));
        }
        metadata.put("sourceReference", record.getExecutionId());
        metadata.put("requestReference", requestReference(record.getRequestSnapshot()));
        addSuiteFailureReference(metadata, record.getResponseSnapshot());
        return metadata;
    }

    private String executionErrorSummary(ExecutionRecord record) {
        if (StringUtils.hasText(record.getErrorMessage())) {
            return record.getErrorMessage();
        }
        var failedAssertions = failedAssertions(record.getAssertionResults());
        if (!failedAssertions.isEmpty()) {
            return failedAssertionSummary(failedAssertions);
        }
        return null;
    }

    private List<Map<String, Object>> failedAssertions(List<Map<String, Object>> assertionResults) {
        return assertionResults.stream()
            .filter(this::assertionFailed)
            .limit(5)
            .map(this::compactAssertion)
            .toList();
    }

    private Map<String, Object> compactAssertion(Map<String, Object> assertion) {
        var compact = new LinkedHashMap<String, Object>();
        copyIfPresent(compact, assertion, "stepId");
        copyIfPresent(compact, assertion, "stepOrder");
        copyIfPresent(compact, assertion, "apiSpecId");
        copyIfPresent(compact, assertion, "name");
        copyIfPresent(compact, assertion, "type");
        copyIfPresent(compact, assertion, "path");
        copyIfPresent(compact, assertion, "expected");
        copyIfPresent(compact, assertion, "actual");
        copyIfPresent(compact, assertion, "status");
        copyIfPresent(compact, assertion, "critical");
        copyIfPresent(compact, assertion, "message");
        return compact;
    }

    private void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private String failedAssertionSummary(List<Map<String, Object>> failedAssertions) {
        return failedAssertions.stream()
            .map(assertion -> {
                var type = stringValue(assertion.get("type"));
                var message = stringValue(assertion.get("message"));
                if (StringUtils.hasText(message)) {
                    return type + ": " + message;
                }
                return type + " expected " + stringValue(assertion.get("expected"))
                    + " but got " + stringValue(assertion.get("actual"));
            })
            .collect(java.util.stream.Collectors.joining("; "));
    }

    private Map<String, Object> requestReference(Map<String, Object> requestSnapshot) {
        var reference = new LinkedHashMap<String, Object>();
        copyIfPresent(reference, requestSnapshot, "suite");
        copyIfPresent(reference, requestSnapshot, "stepCount");
        copyIfPresent(reference, requestSnapshot, "method");
        copyIfPresent(reference, requestSnapshot, "path");
        copyIfPresent(reference, requestSnapshot, "url");
        return reference;
    }

    private void addSuiteFailureReference(Map<String, Object> metadata, Map<String, Object> responseSnapshot) {
        if (!Boolean.TRUE.equals(responseSnapshot.get("suite"))) {
            return;
        }
        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) responseSnapshot.getOrDefault("steps", List.of());
        metadata.put("suite", true);
        metadata.put("stepCount", steps.size());
        steps.stream()
            .filter(step -> {
                var status = step.get("overallStatus");
                return OverallStatus.FAILED.name().equals(status)
                    || OverallStatus.ERROR.name().equals(status)
                    || OverallStatus.BLOCKED.name().equals(status);
            })
            .findFirst()
            .ifPresent(step -> {
                var failedStep = new LinkedHashMap<String, Object>();
                copyIfPresent(failedStep, step, "stepId");
                copyIfPresent(failedStep, step, "order");
                copyIfPresent(failedStep, step, "apiSpecId");
                copyIfPresent(failedStep, step, "overallStatus");
                copyIfPresent(failedStep, step, "message");
                metadata.put("failedStep", failedStep);
            });
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
