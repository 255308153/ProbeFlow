package com.probeflow.testagent.failureanalysis;

import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.memory.MemoryScopeType;
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
    private final TaskRepository tasks;

    public FailureAnalysisApplicationService(
        ExecutionRecordRepository executionRecords,
        ObservationRepository observations,
        TestCaseRepository testCases,
        ApiSpecRepository apiSpecs,
        TaskMemoryService taskMemoryService,
        TaskRepository tasks
    ) {
        this.executionRecords = executionRecords;
        this.observations = observations;
        this.testCases = testCases;
        this.apiSpecs = apiSpecs;
        this.taskMemoryService = taskMemoryService;
        this.tasks = tasks;
    }

    @Transactional
    public FailureAnalysisResult analyzeExecution(FailureAnalysisRequest request) {
        var normalized = normalize(request);
        var record = executionRecords.findById(normalized.executionId())
            .orElseThrow(() -> new IllegalArgumentException("ExecutionRecord not found: " + normalized.executionId()));

        var failedAssertions = failedAssertions(record);
        var suiteFailure = suiteFailure(record);
        var classification = classify(record, failedAssertions, suiteFailure);
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
        var evidence = evidence(record, failedAssertions, classification);
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
            suiteFailure
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
        return new TaskFailureAnalysisResult(
            normalized.taskId(),
            normalized.mode(),
            counts(results),
            results,
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
        SuiteFailureSummary suiteFailure
    ) {
        var response = safeMap(record.getResponseSnapshot());
        var errorType = stringValue(response.get("errorType"));
        var statusCode = record.getStatusCode();

        if (suiteFailure.suiteExecution()
            && suiteFailure.failedStepId() != null
            && suiteFailure.dependentSkippedStepCount() > 0) {
            return FailureClassification.SUITE_PREREQUISITE_FAILURE;
        }
        if (record.getOverallStatus() == OverallStatus.PASSED) {
            return FailureClassification.NONE;
        }
        if (record.getOverallStatus() == OverallStatus.SKIPPED) {
            return FailureClassification.SKIPPED;
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

    private List<String> evidence(
        ExecutionRecord record,
        List<FailedAssertionSummary> failedAssertions,
        FailureClassification classification
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
        var suiteFailure = suiteFailure(record);
        if (suiteFailure.suiteExecution()) {
            evidence.add("suiteStepCount=" + suiteFailure.totalSteps());
            if (suiteFailure.failedStepId() != null) {
                evidence.add("firstFailedStep=" + suiteFailure.failedStepId() + " order=" + suiteFailure.failedStepOrder());
            }
            if (!suiteFailure.dependentSkippedStepIds().isEmpty()) {
                evidence.add("dependentSkippedSteps=" + suiteFailure.dependentSkippedStepIds());
            }
        }
        return List.copyOf(evidence);
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
            case AUTH_ISSUE, VALIDATION_ISSUE, SERVER_ERROR, DURATION_REGRESSION, SUITE_PREREQUISITE_FAILURE -> ObservationType.RISK_EVALUATION;
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
            || classification == FailureClassification.SUITE_PREREQUISITE_FAILURE
            || suiteFailure.dependentSkippedStepCount() > 0) {
            return ObservationRiskLevel.HIGH;
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
        if (classification == FailureClassification.SUITE_PREREQUISITE_FAILURE) {
            return suiteFailure.impactSummary();
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
            case ENVIRONMENT_ISSUE, BLOCKED_REQUEST -> "Inspect environment variables, URL, protocol, and safety policy before retrying.";
            case AUTH_ISSUE -> "Inspect auth variables, token freshness, and token scope.";
            case VALIDATION_ISSUE -> "Review request data, generated TestCase inputs, and API contract expectations.";
            case SERVER_ERROR -> retryable
                ? "Retry once, then investigate API regression or service health if it reproduces."
                : "Investigate API regression or service health.";
            case STATUS_MISMATCH, RESPONSE_SHAPE_MISMATCH, RESPONSE_VALUE_MISMATCH, BODY_PRESENCE_FAILURE -> assetDriftEvidence(record)
                ? "Review API behavior change and update TestCase expectations if the change is intentional."
                : "Investigate API behavior versus TestCase expectations.";
            case DURATION_REGRESSION -> retryable
                ? "Retry once to rule out transient latency, then investigate performance regression."
                : "Investigate API latency and performance regression risk.";
            case SUITE_PREREQUISITE_FAILURE -> "Inspect suite prerequisite step "
                + suiteFailure.failedStepId()
                + " before treating downstream skipped steps as independent failures.";
            case SKIPPED, NONE -> "No failure follow-up is required.";
            default -> "Review deterministic failure analysis evidence.";
        };
    }

    private SuiteFailureSummary suiteFailure(ExecutionRecord record) {
        var response = safeMap(record.getResponseSnapshot());
        if (!Boolean.TRUE.equals(response.get("suite")) || !(response.get("steps") instanceof List<?> rawSteps)) {
            return SuiteFailureSummary.none();
        }

        var steps = rawSteps.stream()
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
        return value != null && value.toLowerCase().contains(needle.toLowerCase());
    }
}
