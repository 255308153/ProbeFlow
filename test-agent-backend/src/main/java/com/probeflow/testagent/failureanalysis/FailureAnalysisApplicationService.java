package com.probeflow.testagent.failureanalysis;

import com.probeflow.testagent.agentmemoryfeedback.AgentMemoryCandidateSourceType;
import com.probeflow.testagent.agentmemoryfeedback.AgentMemoryFeedbackApplicationService;
import com.probeflow.testagent.agentmemoryfeedback.AgentMemoryFeedbackResult;
import com.probeflow.testagent.agentmemoryfeedback.MemoryCandidateProcessingStatus;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemoryCandidateRequest;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.memory.TaskMemoryService;
import com.probeflow.testagent.memory.TaskMemoryWriteRequest;
import com.probeflow.testagent.observation.AnalysisLevel;
import com.probeflow.testagent.observation.Observation;
import com.probeflow.testagent.observation.ObservationRepository;
import com.probeflow.testagent.observation.ObservationRiskLevel;
import com.probeflow.testagent.observation.ObservationSource;
import com.probeflow.testagent.observation.ObservationType;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.testcase.TestCaseRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FailureAnalysisApplicationService {

    private final ExecutionRecordRepository executionRecords;
    private final ObservationRepository observations;
    private final TestCaseRepository testCases;
    private final ApiSpecRepository apiSpecs;
    private final TaskMemoryService taskMemoryService;
    private final AgentMemoryFeedbackApplicationService memoryFeedback;
    private final TaskRepository tasks;

    public FailureAnalysisApplicationService(
        ExecutionRecordRepository executionRecords,
        ObservationRepository observations,
        TestCaseRepository testCases,
        ApiSpecRepository apiSpecs,
        TaskMemoryService taskMemoryService,
        AgentMemoryFeedbackApplicationService memoryFeedback,
        TaskRepository tasks
    ) {
        this.executionRecords = executionRecords;
        this.observations = observations;
        this.testCases = testCases;
        this.apiSpecs = apiSpecs;
        this.taskMemoryService = taskMemoryService;
        this.memoryFeedback = memoryFeedback;
        this.tasks = tasks;
    }

    @Transactional
    public FailureAnalysisResult analyzeExecution(FailureAnalysisRequest request) {
        var normalized = normalize(request);
        var record = executionRecords.findById(normalized.executionId())
            .orElseThrow(() -> new IllegalArgumentException("ExecutionRecord not found: " + normalized.executionId()));

        var failedAssertions = failedAssertions(record);
        var suiteFailure = suiteFailure(record);
        var variableFindings = variableFailures(record);
        var dependencyFailure = dependencyOrderFailure(record);
        var classification = classify(record, failedAssertions, suiteFailure, variableFindings, dependencyFailure);
        var suiteFailureAnalysis = suiteFailureAnalysis(record, classification, suiteFailure, variableFindings, dependencyFailure);
        var riskLevel = riskLevel(record, classification, failedAssertions, suiteFailure);
        var summary = summary(record, classification, suiteFailure);
        var failureReason = failureReason(record, classification, failedAssertions, suiteFailure);
        var retryable = retryable(record, classification);
        var retryReason = retryReason(record, classification, retryable);
        var nextSuggestion = nextSuggestion(record, classification, retryable, suiteFailure);
        var observationIds = writeObservationIfUseful(
            record,
            classification,
            riskLevel,
            summary,
            failureReason,
            nextSuggestion
        );
        var evidence = evidence(record, failedAssertions, classification, suiteFailure, suiteFailureAnalysis);
        var taskMemoryIds = writeTaskMemoryIfUseful(
            record,
            classification,
            riskLevel,
            summary,
            failureReason,
            nextSuggestion,
            retryable,
            evidence
        );
        var memoryCandidate = refineSingleExecutionMemoryCandidate(
            record,
            classification,
            riskLevel,
            summary,
            failureReason,
            retryable,
            evidence
        );
        return new FailureAnalysisResult(
            record.getExecutionId(),
            record.getTaskId(),
            record.getCaseId(),
            record.getStepId(),
            normalized.mode(),
            record.getOverallStatus(),
            record.getStatusCode(),
            record.getDurationMs(),
            record.getEnvironment(),
            requestFacts(record),
            responseFacts(record),
            failedAssertions,
            record.getErrorMessage(),
            classification,
            evidence,
            observationIds,
            riskLevel.name(),
            summary,
            failureReason,
            nextSuggestion,
            retryable,
            retryReason,
            taskMemoryIds,
            memoryCandidate,
            suiteFailure,
            suiteFailureAnalysis
        );
    }

    @Transactional
    public TaskFailureAnalysisResult analyzeTask(TaskFailureAnalysisRequest request) {
        var normalized = normalize(request);
        var records = loadTaskRecords(normalized);
        var results = records.stream()
            .map(record -> analyzeExecution(new FailureAnalysisRequest(record.getExecutionId(), normalized.mode())))
            .toList();
        var groups = groupedFailures(records, results);
        var taskCandidateResults = refineTaskMemoryCandidates(normalized.taskId(), groups, results);
        var enrichedResults = attachTaskMemoryCandidates(results, taskCandidateResults);
        return new TaskFailureAnalysisResult(
            normalized.taskId(),
            normalized.mode(),
            counts(enrichedResults),
            enrichedResults,
            groups
        );
    }

    private FailureAnalysisRequest normalize(FailureAnalysisRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("FailureAnalysisRequest is required");
        }
        var executionId = clean(request.executionId());
        if (executionId == null) {
            throw new IllegalArgumentException("executionId is required");
        }
        var mode = request.mode() == null ? FailureAnalysisMode.BASIC : request.mode();
        if (mode == FailureAnalysisMode.DEEP) {
            throw new IllegalArgumentException("DEEP failure analysis is reserved for a future phase");
        }
        return new FailureAnalysisRequest(executionId, mode);
    }

    private TaskFailureAnalysisRequest normalize(TaskFailureAnalysisRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("TaskFailureAnalysisRequest is required");
        }
        var taskId = clean(request.taskId());
        if (taskId == null) {
            throw new IllegalArgumentException("taskId is required");
        }
        var mode = request.mode() == null ? FailureAnalysisMode.BASIC : request.mode();
        if (mode == FailureAnalysisMode.DEEP) {
            throw new IllegalArgumentException("DEEP failure analysis is reserved for a future phase");
        }
        var executionIds = request.executionIds() == null
            ? List.<String>of()
            : request.executionIds().stream()
                .map(this::clean)
                .filter(id -> id != null)
                .distinct()
                .toList();
        return new TaskFailureAnalysisRequest(taskId, executionIds, mode);
    }

    private List<ExecutionRecord> loadTaskRecords(TaskFailureAnalysisRequest request) {
        if (request.executionIds().isEmpty()) {
            return executionRecords.findAllByTaskIdOrderByCreatedAtAscExecutionIdAsc(request.taskId());
        }
        return executionRecords.findAllByTaskIdAndExecutionIdInOrderByCreatedAtAscExecutionIdAsc(
            request.taskId(),
            request.executionIds()
        );
    }

    private RequestFacts requestFacts(ExecutionRecord record) {
        var snapshot = safeMap(record.getRequestSnapshot());
        return new RequestFacts(
            stringValue(snapshot.get("method")),
            stringValue(snapshot.get("path")),
            stringValue(snapshot.get("url")),
            nestedMap(snapshot.get("headers"))
        );
    }

    private ResponseFacts responseFacts(ExecutionRecord record) {
        var snapshot = safeMap(record.getResponseSnapshot());
        return new ResponseFacts(
            intValue(snapshot.get("statusCode")),
            stringValue(snapshot.get("failureType")),
            stringValue(snapshot.get("errorType")),
            stringValue(snapshot.get("bodyType")),
            booleanValue(snapshot.get("bodyTruncated")),
            longValue(snapshot.get("bodySizeBytes")),
            nestedMap(snapshot.get("headers"))
        );
    }

    private List<FailedAssertionSummary> failedAssertions(ExecutionRecord record) {
        return record.getAssertionResults().stream()
            .filter(assertion -> "FAILED".equals(stringValue(assertion.get("status"))))
            .map(assertion -> new FailedAssertionSummary(
                stringValue(assertion.get("name")),
                stringValue(assertion.get("type")),
                assertion.get("expected"),
                assertion.get("actual"),
                stringValue(assertion.get("path")),
                booleanValue(assertion.get("critical")) == null || Boolean.TRUE.equals(booleanValue(assertion.get("critical"))),
                stringValue(assertion.get("message"))
            ))
            .toList();
    }

    private FailureClassification classify(
        ExecutionRecord record,
        List<FailedAssertionSummary> failedAssertions,
        SuiteFailureSummary suiteFailure,
        List<SuiteVariableFailure> variableFindings,
        SuiteDependencyFailure dependencyFailure
    ) {
        var response = safeMap(record.getResponseSnapshot());
        var errorType = stringValue(response.get("errorType"));
        var statusCode = effectiveStatusCode(record);

        if (record.getOverallStatus() == OverallStatus.PASSED) {
            return FailureClassification.NONE;
        }
        if (record.getOverallStatus() == OverallStatus.SKIPPED) {
            return FailureClassification.SKIPPED;
        }
        var suiteBlocker = suiteBlockingClassification(record);
        if (suiteBlocker != null) {
            return suiteBlocker;
        }
        if (dependencyFailure != null) {
            return FailureClassification.DEPENDENCY_ORDER_FAILURE;
        }
        var variableRoot = rootVariableFailure(variableFindings);
        if (variableRoot != null) {
            return variableRoot.classification();
        }
        if (suiteFailure.dependentSkippedStepCount() == 0 && suiteTimeout(record, errorType)) {
            return FailureClassification.TIMEOUT;
        }
        if (suiteFailure.dependentSkippedStepCount() == 0 && suiteTransportError(record)) {
            return FailureClassification.TRANSPORT_ERROR;
        }
        if (businessPreconditionFailure(record, failedAssertions)) {
            return FailureClassification.BUSINESS_PRECONDITION_FAILURE;
        }
        if (downstreamApiFailure(record)) {
            return FailureClassification.DOWNSTREAM_API_FAILURE;
        }
        if (suiteFailure.suiteExecution()
            && suiteFailure.failedStepId() != null
            && suiteFailure.dependentSkippedStepCount() > 0) {
            return FailureClassification.PREREQUISITE_STEP_FAILURE;
        }
        if (record.getOverallStatus() == OverallStatus.PASSED_WITH_WARNINGS) {
            return warningClassification(failedAssertions);
        }
        if (record.getOverallStatus() == OverallStatus.BLOCKED) {
            return blockedClassification(errorType, record.getErrorMessage());
        }
        if (record.getOverallStatus() == OverallStatus.ERROR) {
            if ("TIMEOUT".equals(errorType) || contains(record.getErrorMessage(), "timeout") || contains(record.getErrorMessage(), "timed out")) {
                return FailureClassification.TIMEOUT;
            }
            return FailureClassification.TRANSPORT_ERROR;
        }
        if (statusCode != null) {
            if (statusCode == 401 || statusCode == 403) {
                return FailureClassification.AUTH_ISSUE;
            }
            if (statusCode == 400 || statusCode == 422) {
                return FailureClassification.VALIDATION_ISSUE;
            }
            if (statusCode >= 500) {
                return FailureClassification.SERVER_ERROR;
            }
        }
        if (!failedAssertions.isEmpty()) {
            return failedAssertionClassification(failedAssertions);
        }
        return FailureClassification.UNKNOWN;
    }

    private Integer effectiveStatusCode(ExecutionRecord record) {
        if (record.getStatusCode() != null) {
            return record.getStatusCode();
        }
        var failedStep = firstFailedSuiteStep(record);
        return failedStep == null ? null : stepStatusCode(failedStep);
    }

    private FailureClassification suiteBlockingClassification(ExecutionRecord record) {
        var failedStep = firstFailedSuiteStep(record);
        if (failedStep == null) {
            return null;
        }
        var statusCode = stepStatusCode(failedStep);
        if (statusCode != null && (statusCode == 401 || statusCode == 403)) {
            return FailureClassification.AUTH_ISSUE;
        }
        var response = nestedMap(failedStep.get("responseSnapshot"));
        var errorType = firstString(failedStep, "errorType", "failureType");
        if (errorType == null) {
            errorType = firstString(response, "errorType", "failureType");
        }
        var message = firstNonBlank(
            stringValue(failedStep.get("message")),
            stringValue(response.get("message")),
            record.getErrorMessage()
        );
        if (contains(errorType, "AUTH")
            || contains(message, "token")
            || contains(message, "authorization")
            || contains(message, "permission")) {
            return FailureClassification.AUTH_ISSUE;
        }
        if ("BLOCKED_HOST".equals(errorType)
            || "INVALID_REQUEST".equals(errorType)
            || contains(message, "baseUrl")
            || contains(message, "tenant")
            || contains(message, "environment variable")
            || contains(message, "env variable")) {
            return FailureClassification.ENVIRONMENT_ISSUE;
        }
        if ("SECURITY_POLICY_BLOCKED".equals(errorType)
            || "POLICY_BLOCKED".equals(errorType)
            || "BLOCKED_REQUEST".equals(errorType)
            || contains(message, "safety policy")
            || contains(message, "security policy")
            || contains(message, "policy blocked")) {
            return FailureClassification.BLOCKED_REQUEST;
        }
        return null;
    }

    private boolean suiteTimeout(ExecutionRecord record, String topLevelErrorType) {
        if ("TIMEOUT".equals(topLevelErrorType) || contains(record.getErrorMessage(), "timeout") || contains(record.getErrorMessage(), "timed out")) {
            return true;
        }
        var failedStep = firstFailedSuiteStep(record);
        if (failedStep == null) {
            return false;
        }
        var response = nestedMap(failedStep.get("responseSnapshot"));
        var errorType = firstNonBlank(stringValue(failedStep.get("errorType")), stringValue(response.get("errorType")));
        var message = firstNonBlank(stringValue(failedStep.get("message")), stringValue(response.get("message")));
        return "TIMEOUT".equals(errorType) || contains(message, "timeout") || contains(message, "timed out");
    }

    private boolean suiteTransportError(ExecutionRecord record) {
        if (record.getOverallStatus() == OverallStatus.ERROR && !suiteFailure(record).suiteExecution()) {
            return true;
        }
        var failedStep = firstFailedSuiteStep(record);
        if (failedStep == null) {
            return false;
        }
        var response = nestedMap(failedStep.get("responseSnapshot"));
        var errorType = firstNonBlank(stringValue(failedStep.get("errorType")), stringValue(response.get("errorType")));
        var message = firstNonBlank(stringValue(failedStep.get("message")), stringValue(response.get("message")));
        return "NETWORK_ERROR".equals(errorType)
            || "TRANSPORT_ERROR".equals(errorType)
            || contains(message, "connection refused")
            || contains(message, "network");
    }

    private FailureClassification warningClassification(List<FailedAssertionSummary> failedAssertions) {
        var failedClassification = failedAssertionClassification(failedAssertions);
        return failedClassification == FailureClassification.UNKNOWN
            ? FailureClassification.PASSED_WITH_WARNING
            : failedClassification;
    }

    private FailureClassification blockedClassification(String errorType, String errorMessage) {
        if ("BLOCKED_HOST".equals(errorType) || "INVALID_REQUEST".equals(errorType) || contains(errorMessage, "Invalid URL")
            || contains(errorMessage, "Unsupported protocol") || contains(errorMessage, "Unresolved variable")) {
            return FailureClassification.ENVIRONMENT_ISSUE;
        }
        return FailureClassification.BLOCKED_REQUEST;
    }

    private FailureClassification failedAssertionClassification(List<FailedAssertionSummary> failedAssertions) {
        if (failedAssertions.stream().anyMatch(assertion -> "STATUS_CODE".equals(assertion.type()))) {
            return FailureClassification.STATUS_MISMATCH;
        }
        if (failedAssertions.stream().anyMatch(assertion -> "JSON_FIELD_EXISTS".equals(assertion.type()))) {
            return FailureClassification.RESPONSE_SHAPE_MISMATCH;
        }
        if (failedAssertions.stream().anyMatch(assertion -> "JSON_FIELD_EQUALS".equals(assertion.type()))) {
            return FailureClassification.RESPONSE_VALUE_MISMATCH;
        }
        if (failedAssertions.stream().anyMatch(assertion -> "BODY_PRESENT".equals(assertion.type()))) {
            return FailureClassification.BODY_PRESENCE_FAILURE;
        }
        if (failedAssertions.stream().anyMatch(assertion -> "DURATION_LESS_THAN_MS".equals(assertion.type()))) {
            return FailureClassification.DURATION_REGRESSION;
        }
        if (!failedAssertions.isEmpty()) {
            return FailureClassification.ASSERTION_FAILURE;
        }
        return FailureClassification.UNKNOWN;
    }

    private List<SuiteVariableFailure> variableFailures(ExecutionRecord record) {
        var findings = new LinkedHashMap<String, SuiteVariableFailure>();
        runtimeDiagnostics(record).stream()
            .map(this::variableFailureFromDiagnostic)
            .filter(finding -> finding != null)
            .forEach(finding -> findings.putIfAbsent(variableFailureKey(finding), finding));
        variableAuditEvents(record).stream()
            .map(this::variableFailureFromAuditEvent)
            .filter(finding -> finding != null)
            .forEach(finding -> findings.putIfAbsent(variableFailureKey(finding), finding));
        return findings.values().stream()
            .sorted(Comparator.comparingInt(this::variableFailurePriority)
                .thenComparing(finding -> finding.stepId() == null ? "" : finding.stepId())
                .thenComparing(finding -> finding.failureReason() == null ? "" : finding.failureReason()))
            .toList();
    }

    private SuiteVariableFailure rootVariableFailure(List<SuiteVariableFailure> findings) {
        return findings == null || findings.isEmpty() ? null : findings.getFirst();
    }

    private SuiteVariableFailure variableFailureFromDiagnostic(Map<String, Object> diagnostic) {
        var code = stringValue(diagnostic.get("code"));
        if (code == null) {
            return null;
        }
        if (isVariableResolutionDiagnostic(diagnostic, code)) {
            return variableFailure(
                FailureClassification.VARIABLE_RESOLUTION_FAILURE,
                diagnostic,
                code
            );
        }
        if ("INVALID_EXTRACT_RULE".equals(code)) {
            return variableFailure(FailureClassification.INVALID_EXTRACT_RULE, diagnostic, code);
        }
        if ("UNSUPPORTED_EXTRACT_SOURCE".equals(code)) {
            return variableFailure(FailureClassification.UNSUPPORTED_EXTRACT_SOURCE, diagnostic, code);
        }
        if ("UNSUPPORTED_WRITE_SCOPE".equals(code)) {
            return variableFailure(FailureClassification.VARIABLE_WRITEBACK_FAILURE, diagnostic, code);
        }
        if (isVariableExtractionDiagnostic(diagnostic, code)) {
            return variableFailure(FailureClassification.VARIABLE_EXTRACTION_FAILURE, diagnostic, code);
        }
        return null;
    }

    private SuiteVariableFailure variableFailureFromAuditEvent(Map<String, Object> event) {
        var eventType = stringValue(event.get("eventType"));
        if ("CONSUMPTION".equals(eventType) && Boolean.FALSE.equals(booleanValue(event.get("resolved")))) {
            return variableFailure(FailureClassification.VARIABLE_RESOLUTION_FAILURE, event, stringValue(event.get("failureReason")));
        }
        if (!"PRODUCTION".equals(eventType)) {
            return null;
        }
        if (Boolean.TRUE.equals(booleanValue(event.get("overwritten")))) {
            return variableFailure(FailureClassification.VARIABLE_OVERWRITE_RISK, event, "VARIABLE_OVERWRITE_RISK");
        }
        if (!Boolean.FALSE.equals(booleanValue(event.get("success")))) {
            return null;
        }
        var reason = stringValue(event.get("failureReason"));
        if ("INVALID_EXTRACT_RULE".equals(reason)) {
            return variableFailure(FailureClassification.INVALID_EXTRACT_RULE, event, reason);
        }
        if ("UNSUPPORTED_EXTRACT_SOURCE".equals(reason)) {
            return variableFailure(FailureClassification.UNSUPPORTED_EXTRACT_SOURCE, event, reason);
        }
        if ("UNSUPPORTED_WRITE_SCOPE".equals(reason)) {
            return variableFailure(FailureClassification.VARIABLE_WRITEBACK_FAILURE, event, reason);
        }
        return variableFailure(FailureClassification.VARIABLE_EXTRACTION_FAILURE, event, reason);
    }

    private SuiteVariableFailure variableFailure(
        FailureClassification classification,
        Map<String, Object> facts,
        String failureReason
    ) {
        return new SuiteVariableFailure(
            classification,
            stringValue(facts.get("stepId")),
            stringValue(facts.get("expression")),
            stringValue(facts.get("location")),
            stringValue(facts.get("scope")),
            stringValue(facts.get("path")),
            firstString(facts, "extractRuleId", "ruleId"),
            stringValue(facts.get("sourceType")),
            stringValue(facts.get("sourcePath")),
            stringValue(facts.get("targetScope")),
            stringValue(facts.get("targetKey")),
            failureReason,
            stringValue(facts.get("oldValueSummary")),
            stringValue(facts.get("newValueSummary"))
        );
    }

    private boolean isVariableResolutionDiagnostic(Map<String, Object> diagnostic, String code) {
        return diagnostic.containsKey("expression")
            || "UNSUPPORTED_VARIABLE_SCOPE".equals(code)
            || "INVALID_VARIABLE_EXPRESSION".equals(code)
            || "UNSUPPORTED_DYNAMIC_FUNCTION".equals(code);
    }

    private boolean isVariableExtractionDiagnostic(Map<String, Object> diagnostic, String code) {
        return diagnostic.containsKey("sourceType")
            || diagnostic.containsKey("sourcePath")
            || diagnostic.containsKey("targetKey")
            || "PATH_MISSING".equals(code)
            || "HEADER_MISSING".equals(code)
            || "INVALID_BODY_JSON_PATH".equals(code)
            || "INVALID_HEADER_PATH".equals(code);
    }

    private List<Map<String, Object>> runtimeDiagnostics(ExecutionRecord record) {
        var response = safeMap(record.getResponseSnapshot());
        var diagnostics = new ArrayList<Map<String, Object>>();
        diagnostics.addAll(mapList(response.get("runtimeDiagnostics")));
        suiteSteps(response).forEach(step -> diagnostics.addAll(mapList(step.get("runtimeDiagnostics"))));
        return distinctMaps(diagnostics);
    }

    private List<Map<String, Object>> variableAuditEvents(ExecutionRecord record) {
        var response = safeMap(record.getResponseSnapshot());
        var events = new ArrayList<Map<String, Object>>();
        events.addAll(mapList(nestedMap(response.get("variableAuditSummary")).get("events")));
        suiteSteps(response).forEach(step -> events.addAll(mapList(step.get("variableEvents"))));
        return distinctMaps(events);
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Map.class::isInstance)
            .map(item -> objectMap((Map<?, ?>) item))
            .toList();
    }

    private List<Map<String, Object>> distinctMaps(List<Map<String, Object>> maps) {
        var distinct = new LinkedHashMap<String, Map<String, Object>>();
        maps.forEach(map -> distinct.putIfAbsent(map.toString(), map));
        return List.copyOf(distinct.values());
    }

    private String variableFailureKey(SuiteVariableFailure finding) {
        return finding.classification()
            + "|" + finding.stepId()
            + "|" + finding.expression()
            + "|" + finding.sourceType()
            + "|" + finding.sourcePath()
            + "|" + finding.targetScope()
            + "|" + finding.targetKey()
            + "|" + finding.failureReason();
    }

    private int variableFailurePriority(SuiteVariableFailure finding) {
        return switch (finding.classification()) {
            case VARIABLE_RESOLUTION_FAILURE -> 10;
            case INVALID_EXTRACT_RULE -> 20;
            case UNSUPPORTED_EXTRACT_SOURCE -> 21;
            case VARIABLE_EXTRACTION_FAILURE -> 22;
            case VARIABLE_WRITEBACK_FAILURE -> 30;
            case VARIABLE_OVERWRITE_RISK -> 90;
            default -> 100;
        };
    }

    private SuiteDependencyFailure dependencyOrderFailure(ExecutionRecord record) {
        var explicit = runtimeDiagnostics(record).stream()
            .filter(diagnostic -> {
                var code = stringValue(diagnostic.get("code"));
                return "DEPENDENCY_ORDER_FAILURE".equals(code) || "VARIABLE_PRODUCER_AFTER_CONSUMER".equals(code);
            })
            .findFirst();
        if (explicit.isPresent()) {
            return dependencyFailureFromDiagnostic(record, explicit.get());
        }

        var stepOrders = suiteStepOrders(record);
        if (stepOrders.isEmpty()) {
            return null;
        }
        var productionEvents = variableAuditEvents(record).stream()
            .filter(event -> "PRODUCTION".equals(stringValue(event.get("eventType"))))
            .filter(event -> !Boolean.FALSE.equals(booleanValue(event.get("success"))))
            .toList();
        for (var consumption : variableAuditEvents(record)) {
            if (!"CONSUMPTION".equals(stringValue(consumption.get("eventType")))) {
                continue;
            }
            var consumerStepId = stringValue(consumption.get("stepId"));
            var consumerOrder = stepOrders.get(consumerStepId);
            if (consumerStepId == null || consumerOrder == null) {
                continue;
            }
            var producer = productionEvents.stream()
                .filter(event -> sameVariable(consumption, event))
                .filter(event -> {
                    var producerOrder = stepOrders.get(stringValue(event.get("stepId")));
                    return producerOrder != null && producerOrder > consumerOrder;
                })
                .min(Comparator.comparingInt(event -> stepOrders.get(stringValue(event.get("stepId")))))
                .orElse(null);
            if (producer == null) {
                continue;
            }
            var producerStepId = stringValue(producer.get("stepId"));
            return new SuiteDependencyFailure(
                producerStepId,
                stepOrders.get(producerStepId),
                consumerStepId,
                consumerOrder,
                stringValue(consumption.get("expression")),
                variableScope(consumption),
                variablePath(consumption),
                variableName(consumption),
                "CONSUMER_BEFORE_PRODUCER"
            );
        }
        return null;
    }

    private SuiteDependencyFailure dependencyFailureFromDiagnostic(ExecutionRecord record, Map<String, Object> diagnostic) {
        var stepOrders = suiteStepOrders(record);
        var producerStepId = firstString(diagnostic, "producerStepId", "producerStep");
        var consumerStepId = firstString(diagnostic, "consumerStepId", "consumerStep", "stepId");
        var producerOrder = intValue(diagnostic.get("producerOrder"));
        var consumerOrder = intValue(diagnostic.get("consumerOrder"));
        if (producerOrder == null && producerStepId != null) {
            producerOrder = stepOrders.get(producerStepId);
        }
        if (consumerOrder == null && consumerStepId != null) {
            consumerOrder = stepOrders.get(consumerStepId);
        }
        return new SuiteDependencyFailure(
            producerStepId,
            producerOrder,
            consumerStepId,
            consumerOrder,
            stringValue(diagnostic.get("expression")),
            variableScope(diagnostic),
            variablePath(diagnostic),
            variableName(diagnostic),
            firstNonBlank(stringValue(diagnostic.get("reason")), stringValue(diagnostic.get("code")))
        );
    }

    private Map<String, Integer> suiteStepOrders(ExecutionRecord record) {
        var ordered = new LinkedHashMap<String, Integer>();
        suiteSteps(safeMap(record.getResponseSnapshot())).forEach(step -> {
            var stepId = stringValue(step.get("stepId"));
            if (stepId != null) {
                ordered.put(stepId, intValue(step.get("order")));
            }
        });
        return ordered;
    }

    private boolean sameVariable(Map<String, Object> consumption, Map<String, Object> production) {
        return valuesEqual(variableScope(consumption), variableScope(production))
            && valuesEqual(variablePath(consumption), variablePath(production));
    }

    private boolean valuesEqual(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }

    private String variableScope(Map<String, Object> event) {
        return firstString(event, "scope", "targetScope");
    }

    private String variablePath(Map<String, Object> event) {
        return firstString(event, "path", "targetKey");
    }

    private String variableName(Map<String, Object> event) {
        var scope = variableScope(event);
        var path = variablePath(event);
        if (scope == null) {
            return path;
        }
        if (path == null) {
            return scope;
        }
        return scope + "." + path;
    }

    private boolean businessPreconditionFailure(
        ExecutionRecord record,
        List<FailedAssertionSummary> failedAssertions
    ) {
        if (!suiteFailure(record).suiteExecution()) {
            return false;
        }
        if (runtimeDiagnostics(record).stream().anyMatch(this::businessPreconditionDiagnostic)) {
            return true;
        }
        var response = safeMap(record.getResponseSnapshot());
        if (businessPreconditionText(firstString(response, "failureType", "errorType", "message"))) {
            return true;
        }
        var failedStep = firstFailedSuiteStep(record);
        if (failedStep != null) {
            var stepResponse = nestedMap(failedStep.get("responseSnapshot"));
            if (businessPreconditionText(firstNonBlank(
                stringValue(failedStep.get("message")),
                stringValue(stepResponse.get("failureType")),
                stringValue(stepResponse.get("errorType")),
                stringValue(stepResponse.get("message"))
            ))) {
                return true;
            }
        }
        return failedAssertions.stream().anyMatch(assertion -> businessPreconditionText(
            firstNonBlank(assertion.name(), assertion.type(), assertion.path(), assertion.message(), stringValue(assertion.actual()))
        ));
    }

    private boolean businessPreconditionDiagnostic(Map<String, Object> diagnostic) {
        return businessPreconditionText(firstNonBlank(
            stringValue(diagnostic.get("code")),
            stringValue(diagnostic.get("failureType")),
            stringValue(diagnostic.get("message"))
        ));
    }

    private boolean businessPreconditionText(String value) {
        return contains(value, "BUSINESS_PRECONDITION")
            || contains(value, "PRECONDITION_FAILED")
            || contains(value, "BUSINESS_STATE_NOT_READY")
            || contains(value, "BUSINESS_RULE")
            || contains(value, "INSUFFICIENT_INVENTORY")
            || contains(value, "inventory")
            || contains(value, "stock")
            || contains(value, "not paid")
            || contains(value, "unpaid")
            || contains(value, "order state")
            || contains(value, "business precondition");
    }

    private boolean downstreamApiFailure(ExecutionRecord record) {
        if (runtimeDiagnostics(record).stream()
            .anyMatch(diagnostic -> "DOWNSTREAM_API_FAILURE".equals(stringValue(diagnostic.get("code"))))) {
            return true;
        }
        var failedStep = firstFailedSuiteStep(record);
        if (failedStep == null) {
            return false;
        }
        var statusCode = stepStatusCode(failedStep);
        if (statusCode == null || statusCode < 500) {
            return false;
        }
        var failedOrder = intValue(failedStep.get("order"));
        if (failedOrder == null || failedOrder <= firstSuiteStepOrder(record)) {
            return false;
        }
        return variableFlowSatisfiedForStep(record, stringValue(failedStep.get("stepId")), failedOrder);
    }

    private int firstSuiteStepOrder(ExecutionRecord record) {
        return suiteSteps(safeMap(record.getResponseSnapshot())).stream()
            .map(step -> intValue(step.get("order")))
            .filter(order -> order != null)
            .min(Integer::compareTo)
            .orElse(Integer.MAX_VALUE);
    }

    private boolean variableFlowSatisfiedForStep(ExecutionRecord record, String stepId, Integer consumerOrder) {
        if (stepId == null || consumerOrder == null) {
            return false;
        }
        var events = variableAuditEvents(record);
        var productions = events.stream()
            .filter(event -> "PRODUCTION".equals(stringValue(event.get("eventType"))))
            .filter(event -> !Boolean.FALSE.equals(booleanValue(event.get("success"))))
            .toList();
        var consumptions = events.stream()
            .filter(event -> "CONSUMPTION".equals(stringValue(event.get("eventType"))))
            .filter(event -> stepId.equals(stringValue(event.get("stepId"))))
            .filter(event -> Boolean.TRUE.equals(booleanValue(event.get("resolved"))))
            .toList();
        if (consumptions.isEmpty()) {
            return false;
        }
        var stepOrders = suiteStepOrders(record);
        return consumptions.stream().allMatch(consumption -> productions.stream()
            .filter(production -> sameVariable(consumption, production))
            .anyMatch(production -> {
                var producerOrder = stepOrders.get(stringValue(production.get("stepId")));
                return producerOrder != null && producerOrder < consumerOrder;
            }));
    }

    private List<String> evidence(
        ExecutionRecord record,
        List<FailedAssertionSummary> failedAssertions,
        FailureClassification classification,
        SuiteFailureSummary suiteFailure,
        SuiteFailureAnalysis suiteFailureAnalysis
    ) {
        var evidence = new ArrayList<String>();
        evidence.add("overallStatus=" + record.getOverallStatus());
        if (record.getStatusCode() != null) {
            evidence.add("statusCode=" + record.getStatusCode());
        }
        if (record.getDurationMs() != null) {
            evidence.add("durationMs=" + record.getDurationMs());
        }
        var response = safeMap(record.getResponseSnapshot());
        var failureType = stringValue(response.get("failureType"));
        if (failureType != null) {
            evidence.add("failureType=" + failureType);
        }
        var errorType = stringValue(response.get("errorType"));
        if (errorType != null) {
            evidence.add("errorType=" + errorType);
        }
        if (record.getErrorMessage() != null) {
            evidence.add("errorMessage=" + record.getErrorMessage());
        }
        failedAssertions.forEach(assertion -> evidence.add(
            "failedAssertion=" + assertion.type()
                + (assertion.path() == null ? "" : " path=" + assertion.path())
                + " expected=" + assertion.expected()
                + " actual=" + assertion.actual()
        ));
        evidence.add("classification=" + classification);
        if (suiteFailure.suiteExecution()) {
            evidence.add("suiteStepCount=" + suiteFailure.totalSteps());
            suiteSteps(response).stream()
                .map(this::suiteStepEvidence)
                .forEach(evidence::add);
            runtimeDiagnostics(record).stream()
                .map(this::runtimeDiagnosticEvidence)
                .forEach(evidence::add);
            if (suiteFailure.failedStepId() != null) {
                evidence.add("firstFailedStep=" + suiteFailure.failedStepId() + " order=" + suiteFailure.failedStepOrder());
                evidence.add("failedStepId=" + suiteFailure.failedStepId());
                evidence.add("failedStepOrder=" + suiteFailure.failedStepOrder());
            }
            if (!suiteFailure.dependentSkippedStepIds().isEmpty()) {
                evidence.add("dependentSkippedSteps=" + suiteFailure.dependentSkippedStepIds());
            }
            suiteFailureAnalysis.dependentSkippedSteps().stream()
                .map(step -> step.skipReason() == null ? step.message() : step.skipReason())
                .filter(reason -> reason != null && !reason.isBlank())
                .distinct()
                .forEach(reason -> evidence.add("skipReason=" + reason));
            suiteFailureAnalysis.variableFindings().forEach(finding -> evidence.add(variableEvidence(finding)));
            if (suiteFailureAnalysis.dependencyFailure() != null) {
                evidence.add(dependencyEvidence(suiteFailureAnalysis.dependencyFailure()));
            }
            if (classification == FailureClassification.DOWNSTREAM_API_FAILURE) {
                successfulVariableFlowEvidence(record).forEach(evidence::add);
            }
        }
        return List.copyOf(evidence);
    }

    private String suiteStepEvidence(Map<String, Object> step) {
        var parts = new ArrayList<String>();
        parts.add("suiteStep=" + stringValue(step.get("stepId")));
        addEvidencePart(parts, "order", stringValue(step.get("order")));
        addEvidencePart(parts, "status", firstNonBlank(stringValue(step.get("overallStatus")), stringValue(step.get("status"))));
        addEvidencePart(parts, "apiSpecId", stringValue(step.get("apiSpecId")));
        addEvidencePart(parts, "statusCode", stringValue(stepStatusCode(step)));
        addEvidencePart(parts, "message", stringValue(step.get("message")));
        return String.join(" ", parts);
    }

    private String runtimeDiagnosticEvidence(Map<String, Object> diagnostic) {
        var parts = new ArrayList<String>();
        parts.add("runtimeDiagnostic=" + stringValue(diagnostic.get("code")));
        addEvidencePart(parts, "stepId", stringValue(diagnostic.get("stepId")));
        addEvidencePart(parts, "message", stringValue(diagnostic.get("message")));
        addEvidencePart(parts, "producerStepId", firstString(diagnostic, "producerStepId", "producerStep"));
        addEvidencePart(parts, "consumerStepId", firstString(diagnostic, "consumerStepId", "consumerStep"));
        addEvidencePart(parts, "scope", variableScope(diagnostic));
        addEvidencePart(parts, "path", variablePath(diagnostic));
        return String.join(" ", parts);
    }

    private String dependencyEvidence(SuiteDependencyFailure failure) {
        var parts = new ArrayList<String>();
        parts.add("dependencyOrderFailure=true");
        addEvidencePart(parts, "producerStepId", failure.producerStepId());
        addEvidencePart(parts, "producerOrder", stringValue(failure.producerOrder()));
        addEvidencePart(parts, "consumerStepId", failure.consumerStepId());
        addEvidencePart(parts, "consumerOrder", stringValue(failure.consumerOrder()));
        addEvidencePart(parts, "variable", failure.variableName());
        addEvidencePart(parts, "expression", failure.expression());
        addEvidencePart(parts, "failureReason", failure.failureReason());
        return String.join(" ", parts);
    }

    private List<String> successfulVariableFlowEvidence(ExecutionRecord record) {
        return variableAuditEvents(record).stream()
            .filter(event -> "CONSUMPTION".equals(stringValue(event.get("eventType"))))
            .filter(event -> Boolean.TRUE.equals(booleanValue(event.get("resolved"))))
            .map(event -> {
                var parts = new ArrayList<String>();
                parts.add("variableFlow=resolved");
                addEvidencePart(parts, "stepId", stringValue(event.get("stepId")));
                addEvidencePart(parts, "expression", stringValue(event.get("expression")));
                addEvidencePart(parts, "scope", variableScope(event));
                addEvidencePart(parts, "path", variablePath(event));
                return String.join(" ", parts);
            })
            .distinct()
            .toList();
    }

    private String variableEvidence(SuiteVariableFailure finding) {
        var parts = new ArrayList<String>();
        parts.add("variableFailure=" + finding.classification());
        addEvidencePart(parts, "stepId", finding.stepId());
        addEvidencePart(parts, "expression", finding.expression());
        addEvidencePart(parts, "location", finding.location());
        addEvidencePart(parts, "scope", finding.scope());
        addEvidencePart(parts, "path", finding.path());
        addEvidencePart(parts, "extractRuleId", finding.extractRuleId());
        addEvidencePart(parts, "sourceType", finding.sourceType());
        addEvidencePart(parts, "sourcePath", finding.sourcePath());
        addEvidencePart(parts, "targetScope", finding.targetScope());
        addEvidencePart(parts, "targetKey", finding.targetKey());
        addEvidencePart(parts, "failureReason", finding.failureReason());
        addEvidencePart(parts, "oldValueSummary", finding.oldValueSummary());
        addEvidencePart(parts, "newValueSummary", finding.newValueSummary());
        return String.join(" ", parts);
    }

    private void addEvidencePart(List<String> parts, String key, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(key + "=" + value);
        }
    }

    private List<String> writeObservationIfUseful(
        ExecutionRecord record,
        FailureClassification classification,
        ObservationRiskLevel riskLevel,
        String summary,
        String failureReason,
        String nextSuggestion
    ) {
        if (!shouldWriteObservation(record)) {
            return List.of();
        }
        var existing = observations.findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
            record.getExecutionId(),
            AnalysisLevel.BASIC
        );
        if (!existing.isEmpty()) {
            return existing.stream().map(Observation::getObservationId).toList();
        }

        var observation = new Observation();
        observation.setTaskId(record.getTaskId());
        observation.setExecutionId(record.getExecutionId());
        observation.setObservationType(observationType(classification));
        observation.setAnalysisLevel(AnalysisLevel.BASIC);
        observation.setSummary(summary);
        observation.setFailureReason(failureReason);
        observation.setRiskLevel(riskLevel);
        observation.setNextSuggestion(nextSuggestion);
        observation.setSource(ObservationSource.SYSTEM);
        var saved = observations.save(observation);
        return List.of(saved.getObservationId());
    }

    private List<String> writeTaskMemoryIfUseful(
        ExecutionRecord record,
        FailureClassification classification,
        ObservationRiskLevel riskLevel,
        String summary,
        String failureReason,
        String nextSuggestion,
        boolean retryable,
        List<String> evidence
    ) {
        if (!shouldWriteTaskMemory(record)) {
            return List.of();
        }

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("classification", classification.name());
        metadata.put("riskLevel", riskLevel.name());
        metadata.put("retryable", retryable);
        metadata.put("nextAction", nextSuggestion);
        metadata.put("executionId", record.getExecutionId());
        metadata.put("caseId", record.getCaseId());
        metadata.put("environment", record.getEnvironment());
        metadata.put("overallStatus", record.getOverallStatus().name());
        if (record.getStatusCode() != null) {
            metadata.put("statusCode", record.getStatusCode());
        }
        var apiReference = apiReference(record);
        if (apiReference != null) {
            metadata.put("apiSpecId", apiReference);
        }
        metadata.put("evidence", evidence.stream().limit(5).toList());

        var content = "Failure analysis: " + failureReason
            + " Next action: " + nextSuggestion
            + " Retryable: " + retryable + ".";
        var write = taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            record.getTaskId(),
            memoryScopeType(classification),
            memorySummary(record, classification),
            content,
            memoryTags(classification, retryable),
            MemorySourceType.EXECUTION_RESULT,
            record.getExecutionId(),
            memoryConfidence(classification, riskLevel),
            "execution",
            metadata,
            null
        ));
        markTaskMemoryRefinementPending(record.getTaskId());
        return List.of(write.memoryId());
    }

    private boolean shouldWriteTaskMemory(ExecutionRecord record) {
        return record.getOverallStatus() == OverallStatus.FAILED
            || record.getOverallStatus() == OverallStatus.ERROR
            || record.getOverallStatus() == OverallStatus.BLOCKED
            || record.getOverallStatus() == OverallStatus.PASSED_WITH_WARNINGS;
    }

    private MemoryScopeType memoryScopeType(FailureClassification classification) {
        return switch (classification) {
            case AUTH_ISSUE, ENVIRONMENT_ISSUE, VALIDATION_ISSUE, DATA_QUALITY_ISSUE -> MemoryScopeType.TESTING_PATTERN;
            default -> MemoryScopeType.FAILURE_PATTERN;
        };
    }

    private String memorySummary(ExecutionRecord record, FailureClassification classification) {
        return classification + " in execution " + record.getExecutionId()
            + " for case " + record.getCaseId();
    }

    private List<String> memoryTags(FailureClassification classification, boolean retryable) {
        var tags = new ArrayList<String>();
        tags.add("phase7");
        tags.add("failure-analysis");
        tags.add(classification.name().toLowerCase());
        if (retryable) {
            tags.add("retryable");
        }
        return List.copyOf(tags);
    }

    private Float memoryConfidence(FailureClassification classification, ObservationRiskLevel riskLevel) {
        if (classification == FailureClassification.UNKNOWN) {
            return 0.60f;
        }
        if (riskLevel == ObservationRiskLevel.HIGH || riskLevel == ObservationRiskLevel.CRITICAL) {
            return 0.88f;
        }
        return 0.78f;
    }

    private MemoryCandidateAnalysisResult refineSingleExecutionMemoryCandidate(
        ExecutionRecord record,
        FailureClassification classification,
        ObservationRiskLevel riskLevel,
        String summary,
        String failureReason,
        boolean retryable,
        List<String> evidence
    ) {
        if (record.getOverallStatus() == OverallStatus.PASSED || record.getOverallStatus() == OverallStatus.SKIPPED) {
            return MemoryCandidateAnalysisResult.notAttempted();
        }
        if (classification == FailureClassification.NONE || classification == FailureClassification.SKIPPED) {
            return MemoryCandidateAnalysisResult.notAttempted();
        }

        var confidence = memoryCandidateConfidence(classification, riskLevel);
        var result = memoryFeedback.refineFailureAnalysisCandidate(
            AgentMemoryCandidateSourceType.EXECUTION_RECORD,
            new MemoryCandidateRequest(
                memoryCandidateSummary(record, classification),
                memoryCandidateContent(record, classification, failureReason, retryable),
                MemorySourceType.EXECUTION_RESULT,
                record.getExecutionId(),
                record.getTaskId(),
                memoryCandidateTags(classification, retryable, false),
                confidence,
                rawEvidence(summary, evidence),
                memoryCandidateMetadata(record, classification, riskLevel, retryable, evidence, 1)
            )
        );
        if (result.memoryId() != null) {
            markTaskMemoryRefinementPending(record.getTaskId());
        }
        return memoryCandidateResult(result);
    }

    private Map<String, MemoryCandidateAnalysisResult> refineTaskMemoryCandidates(
        String taskId,
        List<GroupedFailureSummary> groups,
        List<FailureAnalysisResult> results
    ) {
        var byExecutionId = new LinkedHashMap<String, MemoryCandidateAnalysisResult>();
        var resultById = results.stream()
            .collect(Collectors.toMap(FailureAnalysisResult::executionId, Function.identity()));
        for (var group : groups) {
            if (group.occurrenceCount() < 2 || group.classification() == FailureClassification.DATA_QUALITY_ISSUE) {
                continue;
            }
            var firstExecutionId = group.executionIds().getFirst();
            var first = resultById.get(firstExecutionId);
            if (first == null || first.memoryCandidate().accepted()) {
                continue;
            }
            var retryable = group.retryable();
            var result = memoryFeedback.refineFailureAnalysisCandidate(
                AgentMemoryCandidateSourceType.FAILURE_ANALYSIS,
                new MemoryCandidateRequest(
                    "Repeated " + group.classification() + " pattern for " + group.apiReference(),
                    "Task " + taskId + " saw " + group.occurrenceCount()
                        + " similar executions with classification " + group.classification()
                        + ". Next action: " + first.nextSuggestion(),
                    MemorySourceType.EXECUTION_RESULT,
                    taskId + ":" + group.classification() + ":" + group.apiReference() + ":" + group.statusCode(),
                    taskId,
                    memoryCandidateTags(group.classification(), retryable, true),
                    0.90f,
                    "Repeated task-level failure executions: " + group.executionIds()
                        + ". Cases: " + group.affectedCaseIds()
                        + ". Evidence: " + first.evidence(),
                    groupedMemoryCandidateMetadata(group, taskId, first)
                )
            );
            var candidateResult = memoryCandidateResult(result);
            group.executionIds().forEach(executionId -> byExecutionId.put(executionId, candidateResult));
            if (result.memoryId() != null) {
                markTaskMemoryRefinementPending(taskId);
            }
        }
        return byExecutionId;
    }

    private List<FailureAnalysisResult> attachTaskMemoryCandidates(
        List<FailureAnalysisResult> results,
        Map<String, MemoryCandidateAnalysisResult> taskCandidateResults
    ) {
        if (taskCandidateResults.isEmpty()) {
            return results;
        }
        return results.stream()
            .map(result -> {
                var taskCandidate = taskCandidateResults.get(result.executionId());
                if (taskCandidate == null) {
                    return result;
                }
                return withMemoryCandidate(result, taskCandidate);
            })
            .toList();
    }

    private FailureAnalysisResult withMemoryCandidate(
        FailureAnalysisResult result,
        MemoryCandidateAnalysisResult memoryCandidate
    ) {
        return new FailureAnalysisResult(
            result.executionId(),
            result.taskId(),
            result.caseId(),
            result.stepId(),
            result.mode(),
            result.overallStatus(),
            result.statusCode(),
            result.durationMs(),
            result.environment(),
            result.request(),
            result.response(),
            result.failedAssertions(),
            result.errorMessage(),
            result.classification(),
            result.evidence(),
            result.observationIds(),
            result.riskLevel(),
            result.summary(),
            result.failureReason(),
            result.nextSuggestion(),
            result.retryable(),
            result.retryReason(),
            result.taskMemoryIds(),
            memoryCandidate,
            result.suiteFailure(),
            result.suiteFailureAnalysis()
        );
    }

    private MemoryCandidateAnalysisResult memoryCandidateResult(AgentMemoryFeedbackResult result) {
        var accepted = result.memoryId() != null;
        var duplicateWithMemory = result.status() == MemoryCandidateProcessingStatus.DUPLICATE && result.memoryId() != null;
        return new MemoryCandidateAnalysisResult(
            true,
            accepted,
            result.status() == MemoryCandidateProcessingStatus.ACCEPTED,
            result.status() == MemoryCandidateProcessingStatus.MERGED || duplicateWithMemory,
            result.rejectionReason(),
            result.memoryId()
        );
    }

    private float memoryCandidateConfidence(FailureClassification classification, ObservationRiskLevel riskLevel) {
        if (riskLevel == ObservationRiskLevel.HIGH || riskLevel == ObservationRiskLevel.CRITICAL) {
            return 0.91f;
        }
        return switch (classification) {
            case AUTH_ISSUE, ENVIRONMENT_ISSUE, VALIDATION_ISSUE, TIMEOUT, TRANSPORT_ERROR -> 0.82f;
            default -> 0.42f;
        };
    }

    private String memoryCandidateSummary(ExecutionRecord record, FailureClassification classification) {
        return classification + " failure pattern for " + requestPath(record)
            + " in environment " + record.getEnvironment();
    }

    private String memoryCandidateContent(
        ExecutionRecord record,
        FailureClassification classification,
        String failureReason,
        boolean retryable
    ) {
        return "Execution " + record.getExecutionId()
            + " classified as " + classification
            + " for case " + record.getCaseId()
            + ". Reason: " + failureReason
            + ". Retryable: " + retryable + ".";
    }

    private List<String> memoryCandidateTags(
        FailureClassification classification,
        boolean retryable,
        boolean repeated
    ) {
        var tags = new LinkedHashSet<String>();
        tags.add("phase7");
        tags.add("failure-analysis");
        tags.add(classification.name().toLowerCase());
        switch (classification) {
            case AUTH_ISSUE -> tags.add("auth");
            case ENVIRONMENT_ISSUE, BLOCKED_REQUEST, TIMEOUT, TRANSPORT_ERROR -> tags.add("environment");
            case VALIDATION_ISSUE -> tags.add("validation");
            case SERVER_ERROR, PREREQUISITE_STEP_FAILURE, SUITE_PREREQUISITE_FAILURE -> tags.add("regression-risk");
            default -> {
            }
        }
        if (retryable) {
            tags.add("retry");
        }
        if (repeated) {
            tags.add("historical-failure-pattern");
        }
        return tags.stream().sorted().toList();
    }

    private Map<String, Object> memoryCandidateMetadata(
        ExecutionRecord record,
        FailureClassification classification,
        ObservationRiskLevel riskLevel,
        boolean retryable,
        List<String> evidence,
        int occurrenceCount
    ) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("executionIds", List.of(record.getExecutionId()));
        metadata.put("caseIds", List.of(record.getCaseId()));
        metadata.put("classification", classification.name());
        metadata.put("riskLevel", riskLevel.name());
        metadata.put("retryable", retryable);
        metadata.put("sourceEvidence", evidence.stream().limit(6).toList());
        metadata.put("occurrenceCount", occurrenceCount);
        metadata.put("apiPath", requestPath(record));
        metadata.put("module", requestModule(record));
        if (record.getStatusCode() != null) {
            metadata.put("statusCode", record.getStatusCode());
            metadata.put("errorCode", classification.name() + "_" + record.getStatusCode());
        } else {
            metadata.put("errorCode", classification.name());
        }
        metadata.put("environment", record.getEnvironment());
        return metadata;
    }

    private Map<String, Object> groupedMemoryCandidateMetadata(
        GroupedFailureSummary group,
        String taskId,
        FailureAnalysisResult first
    ) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("taskId", taskId);
        metadata.put("executionIds", group.executionIds());
        metadata.put("caseIds", group.affectedCaseIds());
        metadata.put("classification", group.classification().name());
        metadata.put("riskLevel", group.riskLevel());
        metadata.put("retryable", group.retryable());
        metadata.put("sourceEvidence", first.evidence().stream().limit(6).toList());
        metadata.put("occurrenceCount", group.occurrenceCount());
        metadata.put("apiPath", group.apiReference());
        metadata.put("module", group.apiReference());
        if (group.statusCode() != null) {
            metadata.put("statusCode", group.statusCode());
            metadata.put("errorCode", group.classification().name() + "_" + group.statusCode());
        } else {
            metadata.put("errorCode", group.classification().name());
        }
        return metadata;
    }

    private String rawEvidence(String summary, List<String> evidence) {
        return summary + " Evidence: " + evidence;
    }

    private String requestPath(ExecutionRecord record) {
        var request = safeMap(record.getRequestSnapshot());
        var path = stringValue(request.get("path"));
        if (path != null) {
            return path;
        }
        var url = stringValue(request.get("url"));
        return url == null ? "unknown-api-path" : url;
    }

    private String requestModule(ExecutionRecord record) {
        var path = requestPath(record);
        var parts = path.split("/");
        for (var part : parts) {
            if (!part.isBlank() && !"api".equals(part)) {
                return part;
            }
        }
        return path;
    }

    private void markTaskMemoryRefinementPending(String taskId) {
        tasks.findById(taskId).ifPresent(task -> {
            task.setMemoryRefinementStatus(MemoryRefinementStatus.PENDING);
            tasks.save(task);
        });
    }

    private TaskFailureAnalysisCounts counts(List<FailureAnalysisResult> results) {
        return new TaskFailureAnalysisCounts(
            results.size(),
            (int) results.stream().filter(FailureAnalysisResult::retryable).count(),
            (int) results.stream().filter(result -> !result.retryable()).count(),
            enumCounts(results, result -> result.classification().name()),
            stringCounts(results, FailureAnalysisResult::riskLevel),
            retryabilityCounts(results),
            enumCounts(results, result -> result.overallStatus().name())
        );
    }

    private Map<String, Long> retryabilityCounts(List<FailureAnalysisResult> results) {
        var counts = new TreeMap<String, Long>();
        counts.put("non_retryable", results.stream().filter(result -> !result.retryable()).count());
        counts.put("retryable", results.stream().filter(FailureAnalysisResult::retryable).count());
        return new LinkedHashMap<>(counts);
    }

    private Map<String, Long> enumCounts(List<FailureAnalysisResult> results, Function<FailureAnalysisResult, String> extractor) {
        return stringCounts(results, extractor);
    }

    private Map<String, Long> stringCounts(List<FailureAnalysisResult> results, Function<FailureAnalysisResult, String> extractor) {
        var counts = new TreeMap<String, Long>();
        for (var result : results) {
            counts.merge(extractor.apply(result), 1L, Long::sum);
        }
        return new LinkedHashMap<>(counts);
    }

    private List<GroupedFailureSummary> groupedFailures(
        List<ExecutionRecord> records,
        List<FailureAnalysisResult> results
    ) {
        var recordById = records.stream()
            .collect(Collectors.toMap(ExecutionRecord::getExecutionId, Function.identity()));
        var grouped = results.stream()
            .filter(result -> result.classification() != FailureClassification.NONE)
            .filter(result -> result.classification() != FailureClassification.SKIPPED)
            .collect(Collectors.groupingBy(
                result -> groupingKey(result, recordById.get(result.executionId())),
                LinkedHashMap::new,
                Collectors.toList()
            ));

        var summaries = new ArrayList<GroupedFailureSummary>();
        grouped.values().forEach(group -> summaries.add(groupSummary(group, recordById)));
        dataQualityGroups(records).forEach(summaries::add);
        summaries.sort(
            Comparator.comparingInt((GroupedFailureSummary group) -> severityRank(group.riskLevel())).reversed()
                .thenComparing(group -> group.classification().name())
                .thenComparing(GroupedFailureSummary::apiReference, Comparator.nullsLast(String::compareTo))
                .thenComparing(GroupedFailureSummary::caseReference, Comparator.nullsLast(String::compareTo))
                .thenComparing(group -> group.statusCode() == null ? -1 : group.statusCode())
                .thenComparing(group -> group.executionIds().isEmpty() ? "" : group.executionIds().getFirst())
        );
        return List.copyOf(summaries);
    }

    private String groupingKey(FailureAnalysisResult result, ExecutionRecord record) {
        return result.classification()
            + "|" + apiReference(result, record)
            + "|" + result.caseId()
            + "|" + result.statusCode()
            + "|" + firstFailedAssertionType(result)
            + "|" + result.response().errorType();
    }

    private GroupedFailureSummary groupSummary(
        List<FailureAnalysisResult> group,
        Map<String, ExecutionRecord> recordById
    ) {
        var first = group.getFirst();
        var record = recordById.get(first.executionId());
        return new GroupedFailureSummary(
            first.classification(),
            highestRisk(group),
            group.stream().anyMatch(FailureAnalysisResult::retryable),
            first.statusCode(),
            apiReference(first, record),
            first.caseId(),
            firstFailedAssertionType(first),
            first.response().errorType(),
            distinct(group.stream().map(FailureAnalysisResult::caseId).toList()),
            distinct(group.stream().map(FailureAnalysisResult::executionId).toList()),
            group.size(),
            first.summary()
        );
    }

    private List<GroupedFailureSummary> dataQualityGroups(List<ExecutionRecord> records) {
        var groups = new ArrayList<GroupedFailureSummary>();
        for (var record : records) {
            var missing = missingLinkedRecords(record);
            if (missing.isEmpty()) {
                continue;
            }
            var apiReference = apiReference(record);
            groups.add(new GroupedFailureSummary(
                FailureClassification.DATA_QUALITY_ISSUE,
                ObservationRiskLevel.MEDIUM.name(),
                false,
                record.getStatusCode(),
                apiReference,
                record.getCaseId(),
                null,
                responseFacts(record).errorType(),
                List.of(record.getCaseId()),
                List.of(record.getExecutionId()),
                1,
                "Execution " + record.getExecutionId() + " references missing linked records: " + missing
            ));
        }
        return groups;
    }

    private List<String> missingLinkedRecords(ExecutionRecord record) {
        var missing = new ArrayList<String>();
        if (!testCases.existsById(record.getCaseId())) {
            missing.add("TestCase " + record.getCaseId());
        }
        var apiReference = apiReference(record);
        if (apiReference != null && !apiSpecs.existsById(apiReference)) {
            missing.add("ApiSpec " + apiReference);
        }
        return List.copyOf(missing);
    }

    private String highestRisk(List<FailureAnalysisResult> group) {
        return group.stream()
            .map(FailureAnalysisResult::riskLevel)
            .max(Comparator.comparingInt(this::severityRank))
            .orElse(ObservationRiskLevel.LOW.name());
    }

    private int severityRank(String riskLevel) {
        if (ObservationRiskLevel.CRITICAL.name().equals(riskLevel)) {
            return 4;
        }
        if (ObservationRiskLevel.HIGH.name().equals(riskLevel)) {
            return 3;
        }
        if (ObservationRiskLevel.MEDIUM.name().equals(riskLevel)) {
            return 2;
        }
        return 1;
    }

    private String firstFailedAssertionType(FailureAnalysisResult result) {
        return result.failedAssertions().isEmpty()
            ? null
            : result.failedAssertions().getFirst().type();
    }

    private String apiReference(FailureAnalysisResult result, ExecutionRecord record) {
        if (result.suiteFailure().failedStepApiSpecId() != null) {
            return result.suiteFailure().failedStepApiSpecId();
        }
        return apiReference(record);
    }

    private String apiReference(ExecutionRecord record) {
        if (record == null) {
            return null;
        }
        var request = safeMap(record.getRequestSnapshot());
        var apiSpecId = stringValue(request.get("apiSpecId"));
        if (apiSpecId != null) {
            return apiSpecId;
        }
        var response = safeMap(record.getResponseSnapshot());
        if (Boolean.TRUE.equals(response.get("suite")) && response.get("steps") instanceof List<?> steps) {
            return steps.stream()
                .filter(Map.class::isInstance)
                .map(item -> objectMap((Map<?, ?>) item))
                .filter(this::failedSuiteStep)
                .map(step -> stringValue(step.get("apiSpecId")))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
        }
        return null;
    }

    private List<String> distinct(List<String> values) {
        var ordered = new LinkedHashSet<String>();
        values.stream()
            .filter(value -> value != null && !value.isBlank())
            .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private boolean shouldWriteObservation(ExecutionRecord record) {
        return record.getOverallStatus() == OverallStatus.FAILED
            || record.getOverallStatus() == OverallStatus.ERROR
            || record.getOverallStatus() == OverallStatus.BLOCKED
            || record.getOverallStatus() == OverallStatus.PASSED_WITH_WARNINGS;
    }

    private ObservationType observationType(FailureClassification classification) {
        return switch (classification) {
            case AUTH_ISSUE, VALIDATION_ISSUE, SERVER_ERROR, DURATION_REGRESSION,
                VARIABLE_EXTRACTION_FAILURE, VARIABLE_RESOLUTION_FAILURE, VARIABLE_WRITEBACK_FAILURE,
                VARIABLE_OVERWRITE_RISK, INVALID_EXTRACT_RULE, UNSUPPORTED_EXTRACT_SOURCE,
                DEPENDENCY_ORDER_FAILURE, BUSINESS_PRECONDITION_FAILURE, DOWNSTREAM_API_FAILURE,
                PREREQUISITE_STEP_FAILURE, SUITE_PREREQUISITE_FAILURE -> ObservationType.RISK_EVALUATION;
            case UNKNOWN, NONE, SKIPPED -> ObservationType.GENERAL_COMMENT;
            default -> ObservationType.ASSERTION_FAILURE_ANALYSIS;
        };
    }

    private ObservationRiskLevel riskLevel(
        ExecutionRecord record,
        FailureClassification classification,
        List<FailedAssertionSummary> failedAssertions,
        SuiteFailureSummary suiteFailure
    ) {
        if (record.isCriticalFailed()
            || classification == FailureClassification.SERVER_ERROR
            || classification == FailureClassification.PREREQUISITE_STEP_FAILURE
            || classification == FailureClassification.SUITE_PREREQUISITE_FAILURE
            || classification == FailureClassification.VARIABLE_RESOLUTION_FAILURE
            || classification == FailureClassification.VARIABLE_EXTRACTION_FAILURE
            || classification == FailureClassification.VARIABLE_WRITEBACK_FAILURE
            || classification == FailureClassification.INVALID_EXTRACT_RULE
            || classification == FailureClassification.UNSUPPORTED_EXTRACT_SOURCE
            || classification == FailureClassification.DEPENDENCY_ORDER_FAILURE
            || classification == FailureClassification.BUSINESS_PRECONDITION_FAILURE
            || classification == FailureClassification.DOWNSTREAM_API_FAILURE
            || suiteFailure.dependentSkippedStepCount() > 0) {
            return ObservationRiskLevel.HIGH;
        }
        if (classification == FailureClassification.VARIABLE_OVERWRITE_RISK) {
            return ObservationRiskLevel.MEDIUM;
        }
        if (classification == FailureClassification.AUTH_ISSUE
            || classification == FailureClassification.ENVIRONMENT_ISSUE
            || classification == FailureClassification.TIMEOUT
            || classification == FailureClassification.TRANSPORT_ERROR) {
            return ObservationRiskLevel.MEDIUM;
        }
        if (record.getOverallStatus() == OverallStatus.PASSED_WITH_WARNINGS
            || failedAssertions.stream().noneMatch(FailedAssertionSummary::critical)) {
            return ObservationRiskLevel.LOW;
        }
        return ObservationRiskLevel.MEDIUM;
    }

    private String summary(
        ExecutionRecord record,
        FailureClassification classification,
        SuiteFailureSummary suiteFailure
    ) {
        var summary = "BASIC failure analysis classified execution " + record.getExecutionId()
            + " as " + classification
            + " with status " + record.getOverallStatus();
        if (suiteFailure.suiteExecution() && suiteFailure.failedStepId() != null) {
            summary += "; first failing suite step " + suiteFailure.failedStepId()
                + " order " + suiteFailure.failedStepOrder()
                + " apiSpecId " + suiteFailure.failedStepApiSpecId();
        }
        return summary;
    }

    private String failureReason(
        ExecutionRecord record,
        FailureClassification classification,
        List<FailedAssertionSummary> failedAssertions,
        SuiteFailureSummary suiteFailure
    ) {
        if (classification == FailureClassification.PREREQUISITE_STEP_FAILURE
            || classification == FailureClassification.SUITE_PREREQUISITE_FAILURE) {
            return suiteFailure.impactSummary();
        }
        if (classification == FailureClassification.DEPENDENCY_ORDER_FAILURE) {
            var dependency = dependencyOrderFailure(record);
            if (dependency != null) {
                return "Suite dependency order failure: consumer step " + dependency.consumerStepId()
                    + " order " + dependency.consumerOrder()
                    + " reads " + dependency.variableName()
                    + " before producer step " + dependency.producerStepId()
                    + " order " + dependency.producerOrder();
            }
        }
        if (classification == FailureClassification.BUSINESS_PRECONDITION_FAILURE) {
            return "Deterministic suite evidence indicates a business precondition was not satisfied.";
        }
        if (classification == FailureClassification.DOWNSTREAM_API_FAILURE) {
            var failedStep = firstFailedSuiteStep(record);
            if (failedStep != null) {
                return "Downstream suite step " + stringValue(failedStep.get("stepId"))
                    + " failed with status " + stepStatusCode(failedStep)
                    + " after prerequisite variables were produced and consumed.";
            }
        }
        if (variableClassification(classification)) {
            var variableRoot = rootVariableFailure(variableFailures(record));
            if (variableRoot != null) {
                return "Suite variable failure " + variableRoot.classification()
                    + " at step " + variableRoot.stepId()
                    + variableFailureTarget(variableRoot)
                    + " reason " + variableRoot.failureReason();
            }
        }
        if (record.getErrorMessage() != null && !record.getErrorMessage().isBlank()) {
            return record.getErrorMessage();
        }
        if (!failedAssertions.isEmpty()) {
            var first = failedAssertions.getFirst();
            return first.type() + " expected " + first.expected() + " but got " + first.actual();
        }
        return "Execution evidence indicates " + classification;
    }

    private boolean variableClassification(FailureClassification classification) {
        return classification == FailureClassification.VARIABLE_EXTRACTION_FAILURE
            || classification == FailureClassification.VARIABLE_RESOLUTION_FAILURE
            || classification == FailureClassification.VARIABLE_WRITEBACK_FAILURE
            || classification == FailureClassification.VARIABLE_OVERWRITE_RISK
            || classification == FailureClassification.INVALID_EXTRACT_RULE
            || classification == FailureClassification.UNSUPPORTED_EXTRACT_SOURCE;
    }

    private String variableFailureTarget(SuiteVariableFailure finding) {
        if (finding.expression() != null) {
            return " consuming " + finding.expression()
                + " at " + finding.location()
                + " scope " + finding.scope()
                + " path " + finding.path();
        }
        if (finding.targetKey() != null) {
            return " writing " + finding.targetScope()
                + "." + finding.targetKey()
                + " from " + finding.sourceType()
                + " " + finding.sourcePath();
        }
        return "";
    }

    private boolean retryable(ExecutionRecord record, FailureClassification classification) {
        if (classification == FailureClassification.TIMEOUT || classification == FailureClassification.TRANSPORT_ERROR) {
            return true;
        }
        var statusCode = record.getStatusCode();
        return statusCode != null && (statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504);
    }

    private String retryReason(ExecutionRecord record, FailureClassification classification, boolean retryable) {
        if (!retryable) {
            return "Evidence points to a deterministic or non-retryable failure.";
        }
        if (classification == FailureClassification.TIMEOUT) {
            return "Timeouts are often transient and may succeed on a later attempt.";
        }
        if (classification == FailureClassification.TRANSPORT_ERROR) {
            return "Transport errors can be caused by temporary network or environment instability.";
        }
        return "HTTP " + record.getStatusCode() + " is commonly transient or rate-limit related.";
    }

    private String nextSuggestion(
        ExecutionRecord record,
        FailureClassification classification,
        boolean retryable,
        SuiteFailureSummary suiteFailure
    ) {
        return switch (classification) {
            case TIMEOUT, TRANSPORT_ERROR -> retryable
                ? "Retry execution after checking network and target environment health."
                : "Inspect network and target environment health before retrying.";
            case ENVIRONMENT_ISSUE -> "Inspect environment variables, baseUrl, tenant, URL, and protocol before retrying.";
            case BLOCKED_REQUEST -> "Inspect safety policy, fake gateway configuration, URL, and protocol before retrying.";
            case AUTH_ISSUE -> "Inspect auth variables, token freshness, token scope, and the login step.";
            case VALIDATION_ISSUE -> "Review request data, generated TestCase inputs, and API contract expectations.";
            case SERVER_ERROR -> retryable
                ? "Retry once, then investigate API regression or service health if it reproduces."
                : "Investigate API regression or service health.";
            case STATUS_MISMATCH, RESPONSE_SHAPE_MISMATCH, RESPONSE_VALUE_MISMATCH, BODY_PRESENCE_FAILURE -> assetDriftEvidence(record)
                ? "Review API behavior change and update TestCase expectations if the change is intentional."
                : "Investigate API behavior versus TestCase expectations.";
            case DEPENDENCY_ORDER_FAILURE -> "Send the suite through replanning or manual review to move the producer step before the consumer step.";
            case BUSINESS_PRECONDITION_FAILURE -> "Check business preconditions, seed the required test data, or add a prerequisite step before rerunning the suite.";
            case DOWNSTREAM_API_FAILURE -> "Inspect the downstream API response and service health; prerequisite variables were already produced and consumed.";
            case VARIABLE_EXTRACTION_FAILURE -> "Check response field path, extractRule source mapping, and upstream response shape before rerunning downstream steps.";
            case VARIABLE_RESOLUTION_FAILURE -> "Provide the missing variable, fix the variable reference, or move the producing suite step before this consumer.";
            case VARIABLE_WRITEBACK_FAILURE -> "Check variable target scope, target key, and writeback policy before relying on downstream consumers.";
            case INVALID_EXTRACT_RULE -> "Fix the extractRule target scope, target key, source type, or source path through replanning or manual review.";
            case UNSUPPORTED_EXTRACT_SOURCE -> "Replace the unsupported extract source with BODY_JSON, HEADER, or STATUS_CODE through replanning or manual review.";
            case VARIABLE_OVERWRITE_RISK -> "Review same-name variable overwrite before treating downstream values as stable.";
            case DURATION_REGRESSION -> retryable
                ? "Retry once to rule out transient latency, then investigate performance regression."
                : "Investigate API latency and performance regression risk.";
            case PREREQUISITE_STEP_FAILURE, SUITE_PREREQUISITE_FAILURE -> "Inspect suite prerequisite step "
                + suiteFailure.failedStepId()
                + " before treating downstream skipped steps as independent failures.";
            case SKIPPED, NONE -> "No failure follow-up is required.";
            default -> "Review deterministic failure analysis evidence.";
        };
    }

    private SuiteFailureAnalysis suiteFailureAnalysis(
        ExecutionRecord record,
        FailureClassification classification,
        SuiteFailureSummary suiteFailure,
        List<SuiteVariableFailure> variableFindings,
        SuiteDependencyFailure dependencyFailure
    ) {
        var response = safeMap(record.getResponseSnapshot());
        var steps = suiteSteps(response);
        var variableRoot = rootVariableFailure(variableFindings);
        if (!suiteFailure.suiteExecution() && steps.isEmpty()) {
            return SuiteFailureAnalysis.none();
        }
        if (steps.isEmpty()) {
            return new SuiteFailureAnalysis(
                true,
                classification,
                null,
                null,
                null,
                List.of(),
                List.of(),
                variableRoot,
                variableFindings,
                dependencyFailure,
                0,
                0,
                0,
                suiteFailure.impactSummary()
            );
        }

        var orderedSteps = steps.stream()
            .sorted(Comparator.comparingInt(step -> intValue(step.get("order")) == null ? Integer.MAX_VALUE : intValue(step.get("order"))))
            .toList();
        var skipped = orderedSteps.stream()
            .filter(this::skippedSuiteStep)
            .toList();
        Map<String, Object> firstFailed = null;
        for (var step : orderedSteps) {
            if (failedSuiteStep(step)) {
                firstFailed = step;
                break;
            }
        }
        if (firstFailed == null) {
            return new SuiteFailureAnalysis(
                true,
                classification,
                null,
                null,
                null,
                List.of(),
                List.of(),
                variableRoot,
                variableFindings,
                dependencyFailure,
                orderedSteps.size(),
                skipped.size(),
                0,
                suiteFailure.impactSummary()
            );
        }

        var failedStep = suiteFailureStep(firstFailed);
        var failedOrder = failedStep.order();
        var dependentSkipped = skipped.stream()
            .filter(step -> afterFailedStep(step, failedOrder))
            .filter(step -> prerequisiteSkip(step, failedStep.stepId()))
            .map(this::suiteFailureStep)
            .toList();
        return new SuiteFailureAnalysis(
            true,
            classification,
            failedStep,
            failedStep,
            failedStep,
            dependentSkipped,
            dependentSkipped,
            variableRoot,
            variableFindings,
            dependencyFailure,
            orderedSteps.size(),
            skipped.size(),
            dependentSkipped.size(),
            suiteFailure.impactSummary()
        );
    }

    private boolean afterFailedStep(Map<String, Object> step, Integer failedOrder) {
        var stepOrder = intValue(step.get("order"));
        return failedOrder == null || stepOrder == null || stepOrder > failedOrder;
    }

    private boolean prerequisiteSkip(Map<String, Object> step, String failedStepId) {
        var reason = skipReason(step);
        return contains(reason, "prerequisite step failed") || contains(reason, failedStepId);
    }

    private SuiteFailureStep suiteFailureStep(Map<String, Object> step) {
        var response = nestedMap(step.get("responseSnapshot"));
        var status = stringValue(step.get("overallStatus"));
        if (status == null) {
            status = stringValue(step.get("status"));
        }
        return new SuiteFailureStep(
            stringValue(step.get("stepId")),
            stringValue(step.get("stepName")),
            intValue(step.get("order")),
            stringValue(step.get("apiSpecId")),
            status,
            intValue(step.get("statusCode")) == null ? intValue(response.get("statusCode")) : intValue(step.get("statusCode")),
            stringValue(step.get("message")),
            skipReason(step)
        );
    }

    private String skipReason(Map<String, Object> step) {
        var skipReason = stringValue(step.get("skipReason"));
        return skipReason == null ? stringValue(step.get("message")) : skipReason;
    }

    private List<Map<String, Object>> suiteSteps(Map<String, Object> response) {
        var rawSteps = response.get("steps");
        if (!(rawSteps instanceof List<?>)) {
            rawSteps = response.get("stepResults");
        }
        if (!(rawSteps instanceof List<?> steps)) {
            return List.of();
        }
        return steps.stream()
            .filter(Map.class::isInstance)
            .map(item -> objectMap((Map<?, ?>) item))
            .toList();
    }

    private Map<String, Object> firstFailedSuiteStep(ExecutionRecord record) {
        return suiteSteps(safeMap(record.getResponseSnapshot())).stream()
            .sorted(Comparator.comparingInt(step -> intValue(step.get("order")) == null ? Integer.MAX_VALUE : intValue(step.get("order"))))
            .filter(this::failedSuiteStep)
            .findFirst()
            .orElse(null);
    }

    private Integer stepStatusCode(Map<String, Object> step) {
        var response = nestedMap(step.get("responseSnapshot"));
        var statusCode = intValue(step.get("statusCode"));
        return statusCode == null ? intValue(response.get("statusCode")) : statusCode;
    }

    private SuiteFailureSummary suiteFailure(ExecutionRecord record) {
        var response = safeMap(record.getResponseSnapshot());
        var rawSteps = response.get("steps");
        if (!(rawSteps instanceof List<?>)) {
            rawSteps = response.get("stepResults");
        }
        if (!Boolean.TRUE.equals(response.get("suite")) || !(rawSteps instanceof List<?>)) {
            return SuiteFailureSummary.none();
        }

        var steps = ((List<?>) rawSteps).stream()
            .filter(Map.class::isInstance)
            .map(item -> objectMap((Map<?, ?>) item))
            .toList();
        if (steps.isEmpty()) {
            return new SuiteFailureSummary(true, null, null, null, null, 0, 0, 0, List.of(), "Suite snapshot contains no step results.");
        }

        Map<String, Object> firstFailed = null;
        for (var step : steps) {
            if (failedSuiteStep(step)) {
                firstFailed = step;
                break;
            }
        }

        var skipped = steps.stream()
            .filter(this::skippedSuiteStep)
            .toList();
        var dependentSkipped = new ArrayList<String>();
        if (firstFailed != null) {
            var failedOrder = intValue(firstFailed.get("order"));
            for (var step : skipped) {
                var stepOrder = intValue(step.get("order"));
                if ((failedOrder == null || stepOrder == null || stepOrder > failedOrder)
                    && contains(stringValue(step.get("message")), "prerequisite step failed")) {
                    dependentSkipped.add(stringValue(step.get("stepId")));
                }
            }
        }

        if (firstFailed == null) {
            var impact = skipped.size() == steps.size()
                ? "All suite steps were skipped; no prerequisite failure was identified."
                : "Suite has no failed, errored, or blocked step.";
            return new SuiteFailureSummary(true, null, null, null, null, steps.size(), skipped.size(), 0, List.of(), impact);
        }

        var failedStepId = stringValue(firstFailed.get("stepId"));
        var failedStepOrder = intValue(firstFailed.get("order"));
        var failedStepApiSpecId = stringValue(firstFailed.get("apiSpecId"));
        var failedStepStatus = stringValue(firstFailed.get("overallStatus"));
        if (failedStepStatus == null) {
            failedStepStatus = stringValue(firstFailed.get("status"));
        }
        var impact = "Suite prerequisite step " + failedStepId
            + " (order " + failedStepOrder
            + ", apiSpecId " + failedStepApiSpecId
            + ") failed with status " + failedStepStatus
            + "; dependent skipped steps: " + dependentSkipped;

        return new SuiteFailureSummary(
            true,
            failedStepId,
            failedStepOrder,
            failedStepApiSpecId,
            failedStepStatus,
            steps.size(),
            skipped.size(),
            dependentSkipped.size(),
            List.copyOf(dependentSkipped),
            impact
        );
    }

    private boolean failedSuiteStep(Map<String, Object> step) {
        var overallStatus = stringValue(step.get("overallStatus"));
        var status = stringValue(step.get("status"));
        return "FAILED".equals(overallStatus)
            || "ERROR".equals(overallStatus)
            || "BLOCKED".equals(overallStatus)
            || "FAILED".equals(status)
            || "ERROR".equals(status)
            || "BLOCKED".equals(status);
    }

    private boolean skippedSuiteStep(Map<String, Object> step) {
        return "SKIPPED".equals(stringValue(step.get("overallStatus")))
            || "SKIPPED".equals(stringValue(step.get("status")));
    }

    private boolean assetDriftEvidence(ExecutionRecord record) {
        var response = safeMap(record.getResponseSnapshot());
        return contains(record.getErrorMessage(), "stale")
            || contains(record.getErrorMessage(), "drift")
            || contains(stringValue(response.get("failureType")), "DRIFT")
            || contains(stringValue(response.get("message")), "stale")
            || contains(stringValue(response.get("message")), "drift");
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return objectMap(map);
        }
        return Map.of();
    }

    private Map<String, Object> objectMap(Map<?, ?> map) {
        var copy = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstString(Map<String, Object> map, String... keys) {
        for (var key : keys) {
            var value = stringValue(map.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return null;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private boolean contains(String value, String needle) {
        return value != null && needle != null && value.toLowerCase().contains(needle.toLowerCase());
    }
}
