package com.probeflow.testagent.report;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.observation.AnalysisLevel;
import com.probeflow.testagent.observation.Observation;
import com.probeflow.testagent.observation.ObservationRepository;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecution;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionRepository;
import com.probeflow.testagent.testcase.TestCase;
import com.probeflow.testagent.testcase.TestCaseRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportGenerationApplicationService {

    private final TaskRepository tasks;
    private final TestCaseRepository testCases;
    private final ApiSpecRepository apiSpecs;
    private final TaskCaseExecutionRepository taskCaseExecutions;
    private final ExecutionRecordRepository executionRecords;
    private final ObservationRepository observations;
    private final ReportRepository reports;

    public ReportGenerationApplicationService(
        TaskRepository tasks,
        TestCaseRepository testCases,
        ApiSpecRepository apiSpecs,
        TaskCaseExecutionRepository taskCaseExecutions,
        ExecutionRecordRepository executionRecords,
        ObservationRepository observations,
        ReportRepository reports
    ) {
        this.tasks = tasks;
        this.testCases = testCases;
        this.apiSpecs = apiSpecs;
        this.taskCaseExecutions = taskCaseExecutions;
        this.executionRecords = executionRecords;
        this.observations = observations;
        this.reports = reports;
    }

    @Transactional
    public ReportGenerationResult generateTaskReport(ReportGenerationRequest request) {
        var normalized = normalize(request);
        var task = tasks.findById(normalized.taskId())
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + normalized.taskId()));
        var taskExecutions = taskCaseExecutions.findAllByTaskIdOrderByCaseIdAscIdAsc(task.getTaskId());
        var records = executionRecords.findAllByTaskIdOrderByCreatedAtAscExecutionIdAsc(task.getTaskId());
        var caseIds = caseIds(task, taskExecutions, records);
        var executionSummary = executionSummary(task, caseIds, taskExecutions, records);
        var state = reportState(caseIds.size(), records.size());

        var report = new Report();
        report.setTaskId(task.getTaskId());
        report.setSummary(summary(task, state, caseIds.size(), records.size(), executionSummary));
        report.setCaseCount(caseIds.size());
        report.setPassCount(executionSummary.passed());
        report.setFailCount(executionSummary.failed());
        report.setWarningCount(executionSummary.warning());
        report.setRiskSummary(riskSummary(state, executionSummary));
        var findings = findings(task, state, caseIds, records, executionSummary);
        report.setFindings(findings);
        report.setSuggestions(suggestions(findings));
        report.setMetadata(metadata(task, caseIds, taskExecutions, records, state, executionSummary));

        var saved = reports.save(report);
        return new ReportGenerationResult(
            saved.getReportId(),
            saved.getTaskId(),
            state,
            saved.getCaseCount(),
            records.size()
        );
    }

    private ReportGenerationRequest normalize(ReportGenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ReportGenerationRequest is required");
        }
        var taskId = clean(request.taskId());
        if (taskId == null) {
            throw new IllegalArgumentException("taskId is required");
        }
        return new ReportGenerationRequest(taskId);
    }

    private List<String> caseIds(
        Task task,
        List<TaskCaseExecution> taskExecutions,
        List<ExecutionRecord> records
    ) {
        var caseIds = new LinkedHashSet<String>();
        var targetApiSpecIds = task.getTargetApiSpecIds() == null ? List.<String>of() : task.getTargetApiSpecIds();
        if (!targetApiSpecIds.isEmpty()) {
            testCases.findAllByPrimaryApiSpecIdInOrderByCreatedAtAscCaseIdAsc(targetApiSpecIds)
                .stream()
                .map(TestCase::getCaseId)
                .forEach(caseIds::add);
        }
        taskExecutions.stream()
            .map(TaskCaseExecution::getCaseId)
            .forEach(caseIds::add);
        records.stream()
            .map(ExecutionRecord::getCaseId)
            .forEach(caseIds::add);
        return List.copyOf(caseIds);
    }

    private String reportState(int caseCount, int executionCount) {
        if (caseCount == 0) {
            return "NO_CASES";
        }
        if (executionCount == 0) {
            return "NO_EXECUTIONS";
        }
        return "BASIC_SNAPSHOT";
    }

    private String summary(
        Task task,
        String state,
        int caseCount,
        int executionCount,
        ExecutionSummary executionSummary
    ) {
        return "Task report snapshot for " + task.getTaskName()
            + " (" + task.getTaskId() + ") state=" + state
            + " cases=" + caseCount
            + " executions=" + executionCount
            + " passRate=" + executionSummary.passRate()
            + " unexecuted=" + executionSummary.unexecuted() + ".";
    }

    private String riskSummary(String state, ExecutionSummary executionSummary) {
        if (!executionSummary.hasExecutions()) {
            return switch (state) {
                case "NO_CASES" -> "No test cases are linked to this task yet.";
                case "NO_EXECUTIONS" -> "Test cases are available, but no executions have been recorded yet.";
                default -> "Execution records are available; detailed risk aggregation is reserved for later Phase 8 slices.";
            };
        }
        if (executionSummary.error() > 0 || executionSummary.blocked() > 0) {
            return "Execution summary has blocking risk: error="
                + executionSummary.error()
                + ", blocked="
                + executionSummary.blocked()
                + ", failed="
                + executionSummary.failed()
                + ".";
        }
        if (executionSummary.failed() > 0 || executionSummary.warning() > 0) {
            return "Execution summary has quality risk: failed="
                + executionSummary.failed()
                + ", warning="
                + executionSummary.warning()
                + ".";
        }
        if (executionSummary.unexecuted() > 0 || executionSummary.skipped() > 0) {
            return "Execution summary has coverage gaps: unexecuted="
                + executionSummary.unexecuted()
                + ", skipped="
                + executionSummary.skipped()
                + ".";
        }
        return switch (state) {
            case "NO_CASES" -> "No test cases are linked to this task yet.";
            case "NO_EXECUTIONS" -> "Test cases are available, but no executions have been recorded yet.";
            default -> "All executed records passed with no warnings.";
        };
    }

    private Map<String, Object> noResultFinding(
        String state,
        int caseCount,
        int executionCount,
        ExecutionSummary executionSummary
    ) {
        var finding = new LinkedHashMap<String, Object>();
        finding.put("type", "REPORT_STATE");
        finding.put("severity", "INFO");
        finding.put("state", state);
        finding.put("caseCount", caseCount);
        finding.put("executionCount", executionCount);
        finding.put("unexecutedCaseIds", executionSummary.unexecutedCaseIds());
        finding.put("evidenceType", "FACTUAL");
        finding.put("message", switch (state) {
            case "NO_CASES" -> "No test cases are linked to this task.";
            case "NO_EXECUTIONS" -> "Task has linked test cases but no execution records.";
            default -> executionSummary.unexecuted() > 0
                ? "Task has execution records and generated-but-unexecuted cases."
                : "Task execution records were aggregated into deterministic summary counts.";
        });
        return finding;
    }

    private List<Map<String, Object>> findings(
        Task task,
        String state,
        List<String> caseIds,
        List<ExecutionRecord> records,
        ExecutionSummary executionSummary
    ) {
        var findings = new ArrayList<Map<String, Object>>();
        findings.add(noResultFinding(state, caseIds.size(), records.size(), executionSummary));
        if (records.isEmpty()) {
            return findings;
        }

        var context = findingContext(task, caseIds, records);
        for (var record : records) {
            if (record.getOverallStatus() != OverallStatus.PASSED) {
                findings.add(executionFinding(record, context));
            }
            findings.addAll(dataQualityFindings(record, context));
        }
        return findings.stream()
            .sorted(Comparator
                .comparingInt(this::findingSeverityRank)
                .thenComparing(finding -> sortValue(finding.get("classification")))
                .thenComparing(finding -> sortValue(finding.get("primaryApiSpecId")))
                .thenComparing(finding -> sortValue(finding.get("caseId")))
                .thenComparing(finding -> sortValue(finding.get("executionId")))
                .thenComparing(finding -> sortValue(finding.get("type"))))
            .toList();
    }

    private FindingContext findingContext(Task task, List<String> caseIds, List<ExecutionRecord> records) {
        var caseIdSet = new LinkedHashSet<>(caseIds);
        records.stream().map(ExecutionRecord::getCaseId).forEach(caseIdSet::add);
        var caseById = new LinkedHashMap<String, TestCase>();
        testCases.findAllById(caseIdSet).forEach(testCase -> caseById.put(testCase.getCaseId(), testCase));

        var apiSpecIds = new LinkedHashSet<String>();
        if (task.getTargetApiSpecIds() != null) {
            apiSpecIds.addAll(task.getTargetApiSpecIds());
        }
        caseById.values().stream()
            .map(TestCase::getPrimaryApiSpecId)
            .map(this::clean)
            .filter(value -> value != null)
            .forEach(apiSpecIds::add);
        records.stream()
            .flatMap(record -> apiSpecIds(record).stream())
            .forEach(apiSpecIds::add);

        var apiSpecById = new LinkedHashMap<String, ApiSpec>();
        apiSpecs.findAllById(apiSpecIds).forEach(apiSpec -> apiSpecById.put(apiSpec.getApiSpecId(), apiSpec));

        var observationsByExecutionId = new LinkedHashMap<String, List<Observation>>();
        for (var record : records) {
            observationsByExecutionId.put(
                record.getExecutionId(),
                observations.findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
                    record.getExecutionId(),
                    AnalysisLevel.BASIC
                )
            );
        }
        return new FindingContext(caseById, apiSpecById, observationsByExecutionId);
    }

    private Map<String, Object> executionFinding(ExecutionRecord record, FindingContext context) {
        var classification = classification(record);
        var observationList = context.observationsByExecutionId().getOrDefault(record.getExecutionId(), List.of());
        var apiSpecIds = affectedApiSpecIds(record, context);
        var finding = new LinkedHashMap<String, Object>();
        finding.put("type", "EXECUTION_OUTCOME");
        finding.put("severity", severity(record, classification, observationList));
        finding.put("classification", classification);
        finding.put("groupKey", groupKey(classification, apiSpecIds, record));
        finding.put("caseId", record.getCaseId());
        finding.put("primaryApiSpecId", apiSpecIds.isEmpty() ? null : apiSpecIds.getFirst());
        finding.put("apiSpecIds", apiSpecIds);
        finding.put("executionId", record.getExecutionId());
        finding.put("statusCode", record.getStatusCode());
        finding.put("errorType", stringValue((record.getResponseSnapshot() == null ? Map.<String, Object>of() : record.getResponseSnapshot()).get("errorType")));
        finding.put("assertionTypes", assertionTypes(record));
        finding.put("observationIds", observationList.stream().map(Observation::getObservationId).toList());
        finding.put("retryable", retryable(classification));
        finding.put("evidenceType", observationList.isEmpty() ? "FACTUAL" : "FACTUAL_AND_INFERRED");
        finding.put("factualEvidence", factualEvidence(record, classification));
        finding.put("inferredEvidence", inferredEvidence(observationList));
        finding.put("sourceReferences", sourceReferences(record, observationList, apiSpecIds));
        return finding;
    }

    private List<Map<String, Object>> dataQualityFindings(ExecutionRecord record, FindingContext context) {
        var findings = new ArrayList<Map<String, Object>>();
        if (!context.caseById().containsKey(record.getCaseId())) {
            var finding = dataQualityFinding(
                "MISSING_TEST_CASE",
                "ExecutionRecord references a TestCase that is not available.",
                record,
                List.of()
            );
            finding.put("caseId", record.getCaseId());
            findings.add(finding);
        }

        var apiSpecIds = affectedApiSpecIds(record, context);
        var missingApiSpecIds = apiSpecIds.stream()
            .filter(apiSpecId -> !context.apiSpecById().containsKey(apiSpecId))
            .toList();
        if (!missingApiSpecIds.isEmpty()) {
            var finding = dataQualityFinding(
                "MISSING_API_SPEC",
                "ExecutionRecord or TestCase references ApiSpec ids that are not available.",
                record,
                missingApiSpecIds
            );
            finding.put("caseId", record.getCaseId());
            finding.put("apiSpecIds", missingApiSpecIds);
            finding.put("primaryApiSpecId", missingApiSpecIds.getFirst());
            findings.add(finding);
        }
        return findings;
    }

    private Map<String, Object> dataQualityFinding(
        String type,
        String message,
        ExecutionRecord record,
        List<String> apiSpecIds
    ) {
        var finding = new LinkedHashMap<String, Object>();
        finding.put("type", type);
        finding.put("severity", "MEDIUM");
        finding.put("classification", "DATA_QUALITY_ISSUE");
        finding.put("groupKey", "DATA_QUALITY_ISSUE|" + type + "|" + record.getCaseId() + "|" + String.join(",", apiSpecIds));
        finding.put("executionId", record.getExecutionId());
        finding.put("statusCode", record.getStatusCode());
        finding.put("observationIds", List.of());
        finding.put("retryable", false);
        finding.put("evidenceType", "FACTUAL");
        finding.put("factualEvidence", List.of(message, "executionId=" + record.getExecutionId(), "caseId=" + record.getCaseId()));
        finding.put("inferredEvidence", List.of());
        finding.put("sourceReferences", sourceReferences(record, List.of(), apiSpecIds));
        return finding;
    }

    private List<Map<String, Object>> suggestions(List<Map<String, Object>> findings) {
        var byKey = new LinkedHashMap<String, Map<String, Object>>();
        for (var finding : findings) {
            var classification = clean(stringValue(finding.get("classification")));
            if (classification == null || "NONE".equals(classification)) {
                continue;
            }
            var suggestion = suggestionFor(finding, classification);
            if (suggestion == null) {
                continue;
            }
            var stableKey = stringValue(suggestion.get("stableKey"));
            if (!byKey.containsKey(stableKey)) {
                byKey.put(stableKey, suggestion);
            } else {
                mergeSuggestion(byKey.get(stableKey), suggestion);
            }
        }
        return byKey.values().stream()
            .sorted(Comparator
                .comparingInt((Map<String, Object> suggestion) -> priorityRank(stringValue(suggestion.get("priority"))))
                .thenComparing(suggestion -> sortValue(suggestion.get("category")))
                .thenComparing(suggestion -> sortValue(suggestion.get("classification")))
                .thenComparing(suggestion -> sortValue(suggestion.get("primaryApiSpecId")))
                .thenComparing(suggestion -> sortValue(suggestion.get("action"))))
            .toList();
    }

    private Map<String, Object> suggestionFor(Map<String, Object> finding, String classification) {
        var actionKind = actionKind(finding, classification);
        if (actionKind == null) {
            return null;
        }
        var suggestion = new LinkedHashMap<String, Object>();
        suggestion.put("stableKey", suggestionStableKey(actionKind, finding, classification));
        suggestion.put("category", suggestionCategory(actionKind, classification));
        suggestion.put("priority", suggestionPriority(actionKind, classification, stringValue(finding.get("severity"))));
        suggestion.put("classification", classification);
        suggestion.put("primaryApiSpecId", finding.get("primaryApiSpecId"));
        suggestion.put("statusCode", finding.get("statusCode"));
        suggestion.put("errorType", finding.get("errorType"));
        suggestion.put("assertionTypes", finding.getOrDefault("assertionTypes", List.of()));
        suggestion.put("retryable", Boolean.TRUE.equals(finding.get("retryable")));
        suggestion.put("action", suggestionAction(actionKind, classification));
        suggestion.put("rationale", suggestionRationale(actionKind, classification));
        suggestion.put("findingGroupKeys", List.of(stringValue(finding.get("groupKey"))));
        suggestion.put("sourceReferences", finding.get("sourceReferences"));
        return suggestion;
    }

    private String actionKind(Map<String, Object> finding, String classification) {
        if ("DATA_QUALITY_ISSUE".equals(classification)) {
            return "REPAIR_LINKED_DATA";
        }
        if (Boolean.TRUE.equals(finding.get("retryable"))) {
            return "RETRY_AFTER_STABILIZATION";
        }
        if ("AUTH_ISSUE".equals(classification)) {
            return "INSPECT_AUTH";
        }
        if ("VALIDATION_ISSUE".equals(classification)
            || "STATUS_MISMATCH".equals(classification)
            || "RESPONSE_SHAPE_MISMATCH".equals(classification)
            || "RESPONSE_VALUE_MISMATCH".equals(classification)
            || "BODY_PRESENCE_FAILURE".equals(classification)
            || "DURATION_REGRESSION".equals(classification)
            || "ASSERTION_FAILURE".equals(classification)) {
            return "INVESTIGATE_TEST_OR_CONTRACT";
        }
        if ("BLOCKED_REQUEST".equals(classification)) {
            return "RESOLVE_BLOCKER";
        }
        if ("SKIPPED".equals(classification)) {
            return "REVIEW_SKIPPED_EXECUTION";
        }
        if ("PASSED_WITH_WARNING".equals(classification)) {
            return "REVIEW_WARNING";
        }
        return "INVESTIGATE_FAILURE";
    }

    private String suggestionStableKey(String actionKind, Map<String, Object> finding, String classification) {
        return actionKind + "|"
            + classification + "|"
            + sortValue(finding.get("primaryApiSpecId")) + "|"
            + sortValue(finding.get("statusCode")) + "|"
            + sortValue(finding.get("errorType")) + "|"
            + String.join(",", stringList(finding.get("assertionTypes")));
    }

    private String suggestionCategory(String actionKind, String classification) {
        return switch (actionKind) {
            case "RETRY_AFTER_STABILIZATION" -> "RETRY";
            case "REPAIR_LINKED_DATA" -> "DATA_QUALITY";
            case "INSPECT_AUTH" -> "AUTH";
            case "RESOLVE_BLOCKER" -> "BLOCKER";
            case "REVIEW_SKIPPED_EXECUTION", "REVIEW_WARNING" -> "REVIEW";
            default -> "INVESTIGATION";
        };
    }

    private String suggestionPriority(String actionKind, String classification, String severity) {
        if ("INSPECT_AUTH".equals(actionKind) || "CRITICAL".equals(severity)) {
            return "P0";
        }
        if ("RETRY_AFTER_STABILIZATION".equals(actionKind)
            || "REPAIR_LINKED_DATA".equals(actionKind)
            || "HIGH".equals(severity)) {
            return "P1";
        }
        if ("MEDIUM".equals(severity)) {
            return "P2";
        }
        return "P3";
    }

    private String suggestionAction(String actionKind, String classification) {
        return switch (actionKind) {
            case "RETRY_AFTER_STABILIZATION" -> "Retry affected executions after confirming the target environment is stable.";
            case "REPAIR_LINKED_DATA" -> "Repair missing TestCase or ApiSpec links before relying on this report.";
            case "INSPECT_AUTH" -> "Inspect credentials, tokens and permissions before rerunning affected cases.";
            case "INVESTIGATE_TEST_OR_CONTRACT" -> "Review request data, assertions and API contract with the owning team.";
            case "RESOLVE_BLOCKER" -> "Resolve the blocking request condition before rerunning affected cases.";
            case "REVIEW_SKIPPED_EXECUTION" -> "Review skipped executions and decide whether they should be rerun.";
            case "REVIEW_WARNING" -> "Review warning-level assertion failures and decide whether they should become blocking.";
            default -> "Investigate affected failures with the referenced execution evidence.";
        };
    }

    private String suggestionRationale(String actionKind, String classification) {
        return switch (actionKind) {
            case "RETRY_AFTER_STABILIZATION" -> classification + " is marked retryable by deterministic report classification.";
            case "REPAIR_LINKED_DATA" -> "The report found missing linked TestCase or ApiSpec data, which can make ownership and coverage ambiguous.";
            case "INSPECT_AUTH" -> "Authentication and authorization failures are usually non-retryable until credentials or permissions change.";
            case "INVESTIGATE_TEST_OR_CONTRACT" -> classification + " is non-retryable and needs human review of the test asset or API behavior.";
            case "RESOLVE_BLOCKER" -> "Blocked requests require configuration or safety-policy cleanup before execution can proceed.";
            case "REVIEW_SKIPPED_EXECUTION" -> "Skipped executions are coverage gaps, not independent API failures.";
            case "REVIEW_WARNING" -> "Warnings indicate non-critical assertion failures that still deserve triage.";
            default -> classification + " needs human investigation.";
        };
    }

    @SuppressWarnings("unchecked")
    private void mergeSuggestion(Map<String, Object> target, Map<String, Object> source) {
        target.put("findingGroupKeys", mergeStringLists(target.get("findingGroupKeys"), source.get("findingGroupKeys")));
        var targetRefs = (Map<String, Object>) target.getOrDefault("sourceReferences", Map.of());
        var sourceRefs = (Map<String, Object>) source.getOrDefault("sourceReferences", Map.of());
        var mergedRefs = new LinkedHashMap<String, Object>();
        mergedRefs.put("executionIds", mergeStringLists(targetRefs.get("executionIds"), sourceRefs.get("executionIds")));
        mergedRefs.put("observationIds", mergeStringLists(targetRefs.get("observationIds"), sourceRefs.get("observationIds")));
        mergedRefs.put("caseIds", mergeStringLists(targetRefs.get("caseIds"), sourceRefs.get("caseIds")));
        mergedRefs.put("apiSpecIds", mergeStringLists(targetRefs.get("apiSpecIds"), sourceRefs.get("apiSpecIds")));
        target.put("sourceReferences", mergedRefs);
    }

    private List<String> mergeStringLists(Object first, Object second) {
        var values = new LinkedHashSet<String>();
        values.addAll(stringList(first));
        values.addAll(stringList(second));
        return values.stream().sorted().toList();
    }

    private List<String> assertionTypes(ExecutionRecord record) {
        if (record.getAssertionResults() == null) {
            return List.of();
        }
        return record.getAssertionResults().stream()
            .filter(assertion -> "FAILED".equals(stringValue(assertion.get("status"))))
            .map(assertion -> clean(stringValue(assertion.get("type"))))
            .filter(value -> value != null)
            .distinct()
            .sorted()
            .toList();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .map(this::cleanStringValue)
            .filter(item -> item != null)
            .distinct()
            .toList();
    }

    private int priorityRank(String priority) {
        if ("P0".equals(priority)) {
            return 0;
        }
        if ("P1".equals(priority)) {
            return 1;
        }
        if ("P2".equals(priority)) {
            return 2;
        }
        return 3;
    }

    private List<String> affectedApiSpecIds(ExecutionRecord record, FindingContext context) {
        var apiSpecIds = new LinkedHashSet<String>();
        apiSpecIds.addAll(apiSpecIds(record));
        var testCase = context.caseById().get(record.getCaseId());
        if (testCase != null) {
            var primaryApiSpecId = clean(testCase.getPrimaryApiSpecId());
            if (primaryApiSpecId != null) {
                apiSpecIds.add(primaryApiSpecId);
            }
        }
        return apiSpecIds.stream().sorted().toList();
    }

    private String classification(ExecutionRecord record) {
        var response = record.getResponseSnapshot() == null ? Map.<String, Object>of() : record.getResponseSnapshot();
        var errorType = stringValue(response.get("errorType"));
        if (record.getOverallStatus() == OverallStatus.PASSED_WITH_WARNINGS) {
            return failedAssertionClassification(record, "PASSED_WITH_WARNING");
        }
        if (record.getOverallStatus() == OverallStatus.SKIPPED) {
            return "SKIPPED";
        }
        if (record.getOverallStatus() == OverallStatus.BLOCKED) {
            if ("BLOCKED_HOST".equals(errorType) || "INVALID_REQUEST".equals(errorType)
                || contains(record.getErrorMessage(), "Unresolved variable")
                || contains(record.getErrorMessage(), "Invalid URL")
                || contains(record.getErrorMessage(), "Unsupported protocol")) {
                return "ENVIRONMENT_ISSUE";
            }
            return "BLOCKED_REQUEST";
        }
        if (record.getOverallStatus() == OverallStatus.ERROR) {
            if ("TIMEOUT".equals(errorType) || contains(record.getErrorMessage(), "timeout") || contains(record.getErrorMessage(), "timed out")) {
                return "TIMEOUT";
            }
            return "TRANSPORT_ERROR";
        }
        if (record.getStatusCode() != null) {
            if (record.getStatusCode() == 401 || record.getStatusCode() == 403) {
                return "AUTH_ISSUE";
            }
            if (record.getStatusCode() == 400 || record.getStatusCode() == 422) {
                return "VALIDATION_ISSUE";
            }
            if (record.getStatusCode() >= 500) {
                return "SERVER_ERROR";
            }
        }
        return failedAssertionClassification(record, "UNKNOWN");
    }

    private String failedAssertionClassification(ExecutionRecord record, String fallback) {
        var failedAssertions = record.getAssertionResults() == null
            ? List.<Map<String, Object>>of()
            : record.getAssertionResults().stream()
                .filter(assertion -> "FAILED".equals(stringValue(assertion.get("status"))))
                .toList();
        if (failedAssertions.stream().anyMatch(assertion -> "STATUS_CODE".equals(assertion.get("type")))) {
            return "STATUS_MISMATCH";
        }
        if (failedAssertions.stream().anyMatch(assertion -> "JSON_FIELD_EXISTS".equals(assertion.get("type")))) {
            return "RESPONSE_SHAPE_MISMATCH";
        }
        if (failedAssertions.stream().anyMatch(assertion -> "JSON_FIELD_EQUALS".equals(assertion.get("type")))) {
            return "RESPONSE_VALUE_MISMATCH";
        }
        if (failedAssertions.stream().anyMatch(assertion -> "BODY_PRESENT".equals(assertion.get("type")))) {
            return "BODY_PRESENCE_FAILURE";
        }
        if (failedAssertions.stream().anyMatch(assertion -> "DURATION_LESS_THAN_MS".equals(assertion.get("type")))) {
            return "DURATION_REGRESSION";
        }
        return failedAssertions.isEmpty() ? fallback : "ASSERTION_FAILURE";
    }

    private String severity(ExecutionRecord record, String classification, List<Observation> observationList) {
        var observationSeverity = observationList.stream()
            .map(observation -> observation.getRiskLevel().name())
            .min(Comparator.comparingInt(this::severityRank))
            .orElse(null);
        if (observationSeverity != null) {
            return observationSeverity;
        }
        if ("SERVER_ERROR".equals(classification) || "AUTH_ISSUE".equals(classification)) {
            return "HIGH";
        }
        if ("VALIDATION_ISSUE".equals(classification)
            || "BLOCKED_REQUEST".equals(classification)
            || "ENVIRONMENT_ISSUE".equals(classification)
            || "TRANSPORT_ERROR".equals(classification)
            || "TIMEOUT".equals(classification)) {
            return "MEDIUM";
        }
        if ("SKIPPED".equals(classification) || "PASSED_WITH_WARNING".equals(classification)) {
            return "LOW";
        }
        return "MEDIUM";
    }

    private int findingSeverityRank(Map<String, Object> finding) {
        return severityRank(stringValue(finding.get("severity")));
    }

    private int severityRank(String severity) {
        if ("CRITICAL".equals(severity)) {
            return 0;
        }
        if ("HIGH".equals(severity)) {
            return 1;
        }
        if ("MEDIUM".equals(severity)) {
            return 2;
        }
        if ("LOW".equals(severity)) {
            return 3;
        }
        return 4;
    }

    private boolean retryable(String classification) {
        return "TIMEOUT".equals(classification)
            || "TRANSPORT_ERROR".equals(classification)
            || "SERVER_ERROR".equals(classification)
            || "ENVIRONMENT_ISSUE".equals(classification);
    }

    private String groupKey(String classification, List<String> apiSpecIds, ExecutionRecord record) {
        return classification + "|"
            + (apiSpecIds.isEmpty() ? "NO_API" : String.join(",", apiSpecIds))
            + "|" + record.getCaseId()
            + "|" + (record.getStatusCode() == null ? "NO_STATUS" : record.getStatusCode());
    }

    private List<String> factualEvidence(ExecutionRecord record, String classification) {
        var evidence = new ArrayList<String>();
        evidence.add("overallStatus=" + record.getOverallStatus());
        if (record.getStatusCode() != null) {
            evidence.add("statusCode=" + record.getStatusCode());
        }
        if (record.getDurationMs() != null) {
            evidence.add("durationMs=" + record.getDurationMs());
        }
        var response = record.getResponseSnapshot() == null ? Map.<String, Object>of() : record.getResponseSnapshot();
        var failureType = clean(stringValue(response.get("failureType")));
        if (failureType != null) {
            evidence.add("failureType=" + failureType);
        }
        var errorType = clean(stringValue(response.get("errorType")));
        if (errorType != null) {
            evidence.add("errorType=" + errorType);
        }
        if (clean(record.getErrorMessage()) != null) {
            evidence.add("errorMessage=" + record.getErrorMessage());
        }
        if (record.getAssertionResults() != null) {
            record.getAssertionResults().stream()
                .filter(assertion -> "FAILED".equals(stringValue(assertion.get("status"))))
                .limit(3)
                .map(assertion -> "failedAssertion=" + assertion.get("type")
                    + " expected=" + assertion.get("expected")
                    + " actual=" + assertion.get("actual"))
                .forEach(evidence::add);
        }
        evidence.add("classification=" + classification);
        return List.copyOf(evidence);
    }

    private List<Map<String, Object>> inferredEvidence(List<Observation> observationList) {
        return observationList.stream()
            .map(observation -> {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("observationId", observation.getObservationId());
                evidence.put("observationType", observation.getObservationType().name());
                evidence.put("analysisLevel", observation.getAnalysisLevel().name());
                evidence.put("riskLevel", observation.getRiskLevel().name());
                evidence.put("summary", observation.getSummary());
                evidence.put("failureReason", observation.getFailureReason());
                evidence.put("nextSuggestion", observation.getNextSuggestion());
                evidence.put("source", observation.getSource().name());
                return evidence;
            })
            .toList();
    }

    private Map<String, Object> sourceReferences(
        ExecutionRecord record,
        List<Observation> observationList,
        List<String> apiSpecIds
    ) {
        var sourceReferences = new LinkedHashMap<String, Object>();
        sourceReferences.put("executionIds", List.of(record.getExecutionId()));
        sourceReferences.put("observationIds", observationList.stream().map(Observation::getObservationId).toList());
        sourceReferences.put("caseIds", List.of(record.getCaseId()));
        sourceReferences.put("apiSpecIds", apiSpecIds);
        return sourceReferences;
    }

    private Map<String, Object> metadata(
        Task task,
        List<String> caseIds,
        List<TaskCaseExecution> taskExecutions,
        List<ExecutionRecord> records,
        String state,
        ExecutionSummary executionSummary
    ) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("schemaVersion", "phase8.v1");
        metadata.put("reportType", "TASK_REPORT");
        metadata.put("state", state);
        metadata.put("scope", "HTTP_API_TESTING");
        metadata.put("environment", environment(task, records));
        metadata.put("environments", environments(task, records));
        metadata.put("caseIds", caseIds);
        metadata.put("executionIds", records.stream().map(ExecutionRecord::getExecutionId).toList());
        metadata.put("taskCaseExecutionCount", taskExecutions.size());
        metadata.put("executionSummary", executionSummary.toMetadata());
        metadata.put("coverage", coverageMetadata(task, records));
        metadata.put("executionModes", executionModeCounts(taskExecutions));
        metadata.put("task", taskMetadata(task));
        return metadata;
    }

    private ExecutionSummary executionSummary(
        Task task,
        List<String> caseIds,
        List<TaskCaseExecution> taskExecutions,
        List<ExecutionRecord> records
    ) {
        var executedCaseIds = new LinkedHashSet<String>();
        var counts = new LinkedHashMap<OverallStatus, Integer>();
        for (var status : OverallStatus.values()) {
            counts.put(status, 0);
        }
        var totalDurationMs = 0L;
        var slowestCases = new ArrayList<Map<String, Object>>();
        for (var record : records) {
            executedCaseIds.add(record.getCaseId());
            counts.put(record.getOverallStatus(), counts.get(record.getOverallStatus()) + 1);
            if (record.getDurationMs() != null) {
                totalDurationMs += record.getDurationMs();
            }
            slowestCases.add(slowestCase(record));
        }
        slowestCases.sort(Comparator
            .<Map<String, Object>, Long>comparing(entry -> longValue(entry.get("durationMs")))
            .reversed()
            .thenComparing(entry -> stringValue(entry.get("caseId")))
            .thenComparing(entry -> stringValue(entry.get("executionId"))));

        var unexecutedCaseIds = caseIds.stream()
            .filter(caseId -> !executedCaseIds.contains(caseId))
            .toList();
        var passRate = records.isEmpty()
            ? "0.0000"
            : String.format(java.util.Locale.ROOT, "%.4f", counts.get(OverallStatus.PASSED) / (double) records.size());
        return new ExecutionSummary(
            caseIds.size(),
            records.size(),
            counts.get(OverallStatus.PASSED),
            counts.get(OverallStatus.FAILED),
            counts.get(OverallStatus.PASSED_WITH_WARNINGS),
            counts.get(OverallStatus.ERROR),
            counts.get(OverallStatus.BLOCKED),
            counts.get(OverallStatus.SKIPPED),
            unexecutedCaseIds.size(),
            passRate,
            totalDurationMs,
            unexecutedCaseIds,
            slowestCases.stream().limit(5).toList()
        );
    }

    private Map<String, Object> slowestCase(ExecutionRecord record) {
        var slowest = new LinkedHashMap<String, Object>();
        slowest.put("caseId", record.getCaseId());
        slowest.put("executionId", record.getExecutionId());
        slowest.put("status", record.getOverallStatus().name());
        slowest.put("durationMs", record.getDurationMs() == null ? 0L : record.getDurationMs());
        slowest.put("apiSpecIds", apiSpecIds(record));
        return slowest;
    }

    private Map<String, Object> coverageMetadata(Task task, List<ExecutionRecord> records) {
        var targetApiSpecIds = task.getTargetApiSpecIds() == null ? List.<String>of() : task.getTargetApiSpecIds();
        var testedApiSpecIds = records.stream()
            .flatMap(record -> apiSpecIds(record).stream())
            .distinct()
            .sorted()
            .toList();
        var untestedApiSpecIds = targetApiSpecIds.stream()
            .filter(apiSpecId -> !testedApiSpecIds.contains(apiSpecId))
            .toList();
        var testedTargetApiCount = targetApiSpecIds.stream()
            .filter(testedApiSpecIds::contains)
            .count();
        var coverageRate = targetApiSpecIds.isEmpty()
            ? "0.0000"
            : String.format(java.util.Locale.ROOT, "%.4f", testedTargetApiCount / (double) targetApiSpecIds.size());

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("targetApiSpecIds", targetApiSpecIds);
        metadata.put("testedApiSpecIds", testedApiSpecIds);
        metadata.put("untestedApiSpecIds", untestedApiSpecIds);
        metadata.put("totalTargetApiCount", targetApiSpecIds.size());
        metadata.put("testedTargetApiCount", (int) testedTargetApiCount);
        metadata.put("coverageRate", coverageRate);
        return metadata;
    }

    private List<String> apiSpecIds(ExecutionRecord record) {
        var apiSpecIds = new LinkedHashSet<String>();
        addApiSpecId(apiSpecIds, record.getRequestSnapshot().get("apiSpecId"));
        addApiSpecId(apiSpecIds, record.getRequestSnapshot().get("targetApiSpecId"));
        addApiSpecIdsFromSteps(apiSpecIds, record.getRequestSnapshot().get("steps"));
        addApiSpecIdsFromSteps(apiSpecIds, record.getResponseSnapshot().get("steps"));
        return apiSpecIds.stream().sorted().toList();
    }

    private void addApiSpecIdsFromSteps(LinkedHashSet<String> apiSpecIds, Object value) {
        if (!(value instanceof List<?> steps)) {
            return;
        }
        for (var step : steps) {
            if (step instanceof Map<?, ?> stepMap) {
                addApiSpecId(apiSpecIds, stepMap.get("apiSpecId"));
                addApiSpecId(apiSpecIds, stepMap.get("targetApiSpecId"));
            }
        }
    }

    private void addApiSpecId(LinkedHashSet<String> apiSpecIds, Object value) {
        var apiSpecId = clean(stringValue(value));
        if (apiSpecId != null) {
            apiSpecIds.add(apiSpecId);
        }
    }

    private Map<String, Object> executionModeCounts(List<TaskCaseExecution> taskExecutions) {
        var counts = new LinkedHashMap<String, Object>();
        for (var mode : ExecutionMode.values()) {
            counts.put(mode.name(), 0);
        }
        for (var execution : taskExecutions) {
            counts.put(execution.getExecutionMode().name(), ((Number) counts.get(execution.getExecutionMode().name())).intValue() + 1);
        }
        return counts;
    }

    private Map<String, Object> taskMetadata(Task task) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("taskId", task.getTaskId());
        metadata.put("taskName", task.getTaskName());
        metadata.put("taskType", task.getTaskType().name());
        metadata.put("status", task.getStatus().name());
        metadata.put("sourceType", task.getSourceType().name());
        metadata.put("sourceRef", task.getSourceRef());
        metadata.put("targetApiSpecIds", task.getTargetApiSpecIds());
        metadata.put("priority", task.getPriority().name());
        metadata.put("creator", task.getCreator());
        metadata.put("metadata", task.getMetadata());
        metadata.put("createdAt", task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
        metadata.put("updatedAt", task.getUpdatedAt() == null ? null : task.getUpdatedAt().toString());
        return metadata;
    }

    private String environment(Task task, List<ExecutionRecord> records) {
        var environments = environments(task, records);
        return environments.isEmpty() ? null : environments.getFirst();
    }

    private List<String> environments(Task task, List<ExecutionRecord> records) {
        var values = new ArrayList<String>();
        records.stream()
            .map(ExecutionRecord::getEnvironment)
            .map(this::clean)
            .filter(value -> value != null)
            .forEach(values::add);
        var taskEnvironment = clean(stringValue(task.getMetadata().get("environment")));
        if (taskEnvironment != null) {
            values.add(taskEnvironment);
        }
        return values.stream().distinct().sorted().toList();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String sortValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String cleanStringValue(Object value) {
        return clean(stringValue(value));
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

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean contains(String value, String fragment) {
        return value != null && fragment != null
            && value.toLowerCase(java.util.Locale.ROOT).contains(fragment.toLowerCase(java.util.Locale.ROOT));
    }

    private record ExecutionSummary(
        int total,
        int executionCount,
        int passed,
        int failed,
        int warning,
        int error,
        int blocked,
        int skipped,
        int unexecuted,
        String passRate,
        long totalDurationMs,
        List<String> unexecutedCaseIds,
        List<Map<String, Object>> slowestCases
    ) {

        private boolean hasExecutions() {
            return executionCount > 0;
        }

        private Map<String, Object> toMetadata() {
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("total", total);
            metadata.put("executionCount", executionCount);
            metadata.put("passed", passed);
            metadata.put("failed", failed);
            metadata.put("warning", warning);
            metadata.put("error", error);
            metadata.put("blocked", blocked);
            metadata.put("skipped", skipped);
            metadata.put("unexecuted", unexecuted);
            metadata.put("passRate", passRate);
            metadata.put("totalDurationMs", totalDurationMs);
            metadata.put("unexecutedCaseIds", unexecutedCaseIds);
            metadata.put("slowestCases", slowestCases);
            return metadata;
        }
    }

    private record FindingContext(
        Map<String, TestCase> caseById,
        Map<String, ApiSpec> apiSpecById,
        Map<String, List<Observation>> observationsByExecutionId
    ) {
    }
}
