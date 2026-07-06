package com.probeflow.testagent.report;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.failureanalysis.FailureAnalysisApplicationService;
import com.probeflow.testagent.failureanalysis.FailureAnalysisRequest;
import com.probeflow.testagent.memory.LongTermMemory;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.memory.MemoryStatus;
import com.probeflow.testagent.memory.TaskMemoryService;
import com.probeflow.testagent.memory.TaskMemoryView;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    private final FailureAnalysisApplicationService failureAnalysis;
    private final TaskMemoryService taskMemoryService;
    private final LongTermMemoryRepository longTermMemories;
    private final ReportRepository reports;

    public ReportGenerationApplicationService(
        TaskRepository tasks,
        TestCaseRepository testCases,
        ApiSpecRepository apiSpecs,
        TaskCaseExecutionRepository taskCaseExecutions,
        ExecutionRecordRepository executionRecords,
        ObservationRepository observations,
        FailureAnalysisApplicationService failureAnalysis,
        TaskMemoryService taskMemoryService,
        LongTermMemoryRepository longTermMemories,
        ReportRepository reports
    ) {
        this.tasks = tasks;
        this.testCases = testCases;
        this.apiSpecs = apiSpecs;
        this.taskCaseExecutions = taskCaseExecutions;
        this.executionRecords = executionRecords;
        this.observations = observations;
        this.failureAnalysis = failureAnalysis;
        this.taskMemoryService = taskMemoryService;
        this.longTermMemories = longTermMemories;
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
        var analysisCoverage = ensureBasicAnalysis(records);
        var state = reportState(caseIds.size(), records.size());
        var generatedAt = Instant.now();

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
        report.setMetadata(metadata(task, caseIds, taskExecutions, records, state, executionSummary, analysisCoverage, generatedAt));

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
        var suiteImpact = suiteExecutionImpact(record);
        var classification = classification(record);
        var observationList = context.observationsByExecutionId().getOrDefault(record.getExecutionId(), List.of());
        var apiSpecIds = affectedApiSpecIds(record, context);
        var finding = new LinkedHashMap<String, Object>();
        finding.put("type", "EXECUTION_OUTCOME");
        finding.put("severity", severity(record, classification, observationList));
        finding.put("classification", classification);
        finding.put("groupKey", groupKey(classification, apiSpecIds, record));
        finding.put("caseId", record.getCaseId());
        finding.put("primaryApiSpecId", primaryApiSpecId(apiSpecIds, suiteImpact));
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
        if (suiteImpact.suiteExecution()) {
            finding.put("suiteExecution", true);
            finding.put("suiteStepCount", suiteImpact.totalSteps());
            finding.put("firstFailingStep", suiteImpact.firstFailingStep());
            finding.put("dependentSkippedSteps", suiteImpact.dependentSkippedSteps());
            finding.put("dependentSkippedStepIds", suiteImpact.dependentSkippedStepIds());
            finding.put("dependentSkippedStepCount", suiteImpact.dependentSkippedStepIds().size());
            finding.put("suiteImpactSummary", suiteImpact.impactSummary());
        }
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
                .thenComparingInt(suggestion -> suggestionCategoryRank(stringValue(suggestion.get("category"))))
                .thenComparing(suggestion -> sortValue(suggestion.get("category")))
                .thenComparing(suggestion -> sortValue(suggestion.get("classification")))
                .thenComparing(suggestion -> sortValue(suggestion.get("primaryApiSpecId")))
                .thenComparing(suggestion -> sortValue(suggestion.get("action"))))
            .toList();
    }

    private AnalysisCoverage ensureBasicAnalysis(List<ExecutionRecord> records) {
        var eligibleExecutionIds = new ArrayList<String>();
        var existingAnalysisExecutionIds = new ArrayList<String>();
        var generatedAnalysisExecutionIds = new ArrayList<String>();
        var generatedObservationIds = new ArrayList<String>();
        var missingAnalysisExecutionIds = new ArrayList<String>();
        var ineligibleExecutionIds = new ArrayList<String>();
        var generatedCandidateResults = new ArrayList<Map<String, Object>>();

        for (var record : records) {
            if (!requiresBasicAnalysis(record)) {
                ineligibleExecutionIds.add(record.getExecutionId());
                continue;
            }
            eligibleExecutionIds.add(record.getExecutionId());
            var existing = observations.findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
                record.getExecutionId(),
                AnalysisLevel.BASIC
            );
            if (!existing.isEmpty()) {
                existingAnalysisExecutionIds.add(record.getExecutionId());
                continue;
            }

            var result = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(record.getExecutionId()));
            generatedCandidateResults.add(memoryCandidateResult(record.getExecutionId(), result.memoryCandidate()));
            if (result.observationIds().isEmpty()) {
                missingAnalysisExecutionIds.add(record.getExecutionId());
            } else {
                generatedAnalysisExecutionIds.add(record.getExecutionId());
                generatedObservationIds.addAll(result.observationIds());
            }
        }

        return new AnalysisCoverage(
            eligibleExecutionIds,
            existingAnalysisExecutionIds,
            generatedAnalysisExecutionIds,
            generatedObservationIds,
            missingAnalysisExecutionIds,
            ineligibleExecutionIds,
            generatedCandidateResults
        );
    }

    private Map<String, Object> memoryCandidateResult(
        String executionId,
        com.probeflow.testagent.failureanalysis.MemoryCandidateAnalysisResult candidate
    ) {
        var result = new LinkedHashMap<String, Object>();
        result.put("executionId", executionId);
        result.put("attempted", candidate.attempted());
        result.put("accepted", candidate.accepted());
        result.put("created", candidate.created());
        result.put("merged", candidate.merged());
        result.put("rejectionReason", candidate.rejectionReason());
        result.put("memoryId", candidate.memoryId());
        return result;
    }

    private boolean requiresBasicAnalysis(ExecutionRecord record) {
        return record.getOverallStatus() == OverallStatus.FAILED
            || record.getOverallStatus() == OverallStatus.ERROR
            || record.getOverallStatus() == OverallStatus.BLOCKED
            || record.getOverallStatus() == OverallStatus.PASSED_WITH_WARNINGS;
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
            || "SUITE_PREREQUISITE_FAILURE".equals(classification)
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
        if ("SUITE_PREREQUISITE_FAILURE".equals(classification)) {
            return "Inspect the first failing suite step before rerunning dependent skipped steps.";
        }
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

    private int suggestionCategoryRank(String category) {
        if ("AUTH".equals(category)) {
            return 0;
        }
        if ("DATA_QUALITY".equals(category)) {
            return 1;
        }
        if ("RETRY".equals(category)) {
            return 2;
        }
        if ("BLOCKER".equals(category)) {
            return 3;
        }
        if ("INVESTIGATION".equals(category)) {
            return 4;
        }
        if ("REVIEW".equals(category)) {
            return 5;
        }
        return 6;
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

    private Object primaryApiSpecId(List<String> apiSpecIds, SuiteExecutionImpact suiteImpact) {
        var failedStepApiSpecId = suiteImpact.failedStepApiSpecId();
        if (failedStepApiSpecId != null) {
            return failedStepApiSpecId;
        }
        return apiSpecIds.isEmpty() ? null : apiSpecIds.getFirst();
    }

    private String classification(ExecutionRecord record) {
        var suiteImpact = suiteExecutionImpact(record);
        if (suiteImpact.suiteExecution()
            && suiteImpact.failedStepId() != null
            && !suiteImpact.dependentSkippedStepIds().isEmpty()) {
            return "SUITE_PREREQUISITE_FAILURE";
        }
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
        if ("SUITE_PREREQUISITE_FAILURE".equals(classification)) {
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
        var suiteImpact = suiteExecutionImpact(record);
        if (suiteImpact.suiteExecution()) {
            evidence.add("suiteStepCount=" + suiteImpact.totalSteps());
            if (suiteImpact.failedStepId() != null) {
                evidence.add("firstFailedStep=" + suiteImpact.failedStepId()
                    + " order=" + suiteImpact.failedStepOrder());
            }
            if (!suiteImpact.dependentSkippedStepIds().isEmpty()) {
                evidence.add("dependentSkippedSteps=" + suiteImpact.dependentSkippedStepIds());
            }
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
        var suiteImpact = suiteExecutionImpact(record);
        if (suiteImpact.suiteExecution()) {
            sourceReferences.put("suiteStepIds", suiteImpact.stepIds());
            sourceReferences.put("firstFailingStepId", suiteImpact.failedStepId());
            sourceReferences.put("dependentSkippedStepIds", suiteImpact.dependentSkippedStepIds());
        }
        return sourceReferences;
    }

    private Map<String, Object> metadata(
        Task task,
        List<String> caseIds,
        List<TaskCaseExecution> taskExecutions,
        List<ExecutionRecord> records,
        String state,
        ExecutionSummary executionSummary,
        AnalysisCoverage analysisCoverage,
        Instant generatedAt
    ) {
        var sourceState = sourceState(task, caseIds, taskExecutions, records);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("schemaVersion", "phase8.v1");
        metadata.put("reportType", "TASK_REPORT");
        metadata.put("state", state);
        metadata.put("scope", "HTTP_API_TESTING");
        metadata.put("generatedAt", generatedAt.toString());
        metadata.put("snapshot", snapshotMetadata(generatedAt, sourceState));
        metadata.put("environment", environment(task, records));
        metadata.put("environments", environments(task, records));
        metadata.put("caseIds", caseIds);
        metadata.put("executionIds", records.stream().map(ExecutionRecord::getExecutionId).toList());
        metadata.put("taskCaseExecutionCount", taskExecutions.size());
        metadata.put("sourceState", sourceState);
        metadata.put("executionSummary", executionSummary.toMetadata());
        metadata.put("coverage", coverageMetadata(task, records));
        metadata.put("executionModes", executionModeCounts(taskExecutions));
        metadata.put("suiteCoverage", suiteCoverage(records));
        metadata.put("analysisCoverage", analysisCoverage.toMetadata());
        metadata.put("staleness", stalenessMetadata(sourceState, analysisCoverage));
        metadata.put("memoryFeedback", memoryFeedback(task, records, analysisCoverage));
        metadata.put("task", taskMetadata(task));
        return metadata;
    }

    private Map<String, Object> snapshotMetadata(Instant generatedAt, Map<String, Object> sourceState) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("immutable", true);
        metadata.put("generationMode", "CREATE_NEW");
        metadata.put("generatedAt", generatedAt.toString());
        metadata.put("sourceFingerprint", sourceState.get("fingerprint"));
        return metadata;
    }

    private Map<String, Object> stalenessMetadata(Map<String, Object> sourceState, AnalysisCoverage analysisCoverage) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("sourceFingerprint", sourceState.get("fingerprint"));
        metadata.put("analysisComplete", analysisCoverage.missingAnalysisExecutionIds().isEmpty());
        metadata.put("staleAnalysis", !analysisCoverage.missingAnalysisExecutionIds().isEmpty());
        metadata.put("changedInputsDetected", false);
        metadata.put("reason", "Report is an immutable snapshot; compare sourceFingerprint with a newer report to detect changed inputs.");
        return metadata;
    }

    private Map<String, Object> sourceState(
        Task task,
        List<String> caseIds,
        List<TaskCaseExecution> taskExecutions,
        List<ExecutionRecord> records
    ) {
        var state = new LinkedHashMap<String, Object>();
        state.put("taskId", task.getTaskId());
        state.put("taskUpdatedAt", task.getUpdatedAt() == null ? null : task.getUpdatedAt().toString());
        state.put("caseIds", caseIds);
        state.put("taskCaseExecutionIds", taskExecutions.stream()
            .map(TaskCaseExecution::getId)
            .sorted()
            .toList());
        state.put("executionCount", records.size());
        state.put("executionIds", records.stream().map(ExecutionRecord::getExecutionId).toList());
        state.put("latestExecutionCreatedAt", records.stream()
            .map(ExecutionRecord::getCreatedAt)
            .filter(value -> value != null)
            .max(Comparator.naturalOrder())
            .map(Instant::toString)
            .orElse(null));
        state.put("executions", records.stream().map(this::executionSourceState).toList());
        state.put("basicObservationIds", records.stream()
            .flatMap(record -> observations.findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
                record.getExecutionId(),
                AnalysisLevel.BASIC
            ).stream())
            .map(Observation::getObservationId)
            .sorted()
            .toList());
        state.put("fingerprint", fingerprint(state));
        return state;
    }

    private Map<String, Object> executionSourceState(ExecutionRecord record) {
        var state = new LinkedHashMap<String, Object>();
        state.put("executionId", record.getExecutionId());
        state.put("caseId", record.getCaseId());
        state.put("overallStatus", record.getOverallStatus().name());
        state.put("statusCode", record.getStatusCode());
        state.put("durationMs", record.getDurationMs());
        state.put("environment", record.getEnvironment());
        state.put("apiSpecIds", apiSpecIds(record));
        state.put("createdAt", record.getCreatedAt() == null ? null : record.getCreatedAt().toString());
        return state;
    }

    private String fingerprint(Map<String, Object> state) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(stableString(state).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private String stableString(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, nestedValue) -> sorted.put(String.valueOf(key), nestedValue));
            var builder = new StringBuilder("{");
            sorted.forEach((key, nestedValue) -> builder.append(key).append(":").append(stableString(nestedValue)).append(";"));
            return builder.append("}").toString();
        }
        if (value instanceof List<?> list) {
            var builder = new StringBuilder("[");
            list.forEach(item -> builder.append(stableString(item)).append(";"));
            return builder.append("]").toString();
        }
        return String.valueOf(value);
    }

    private Map<String, Object> memoryFeedback(
        Task task,
        List<ExecutionRecord> records,
        AnalysisCoverage analysisCoverage
    ) {
        var executionIds = new LinkedHashSet<String>();
        records.stream()
            .map(ExecutionRecord::getExecutionId)
            .forEach(executionIds::add);
        var caseIds = new LinkedHashSet<String>();
        records.stream()
            .map(ExecutionRecord::getCaseId)
            .forEach(caseIds::add);
        var taskMemoryReferences = taskMemoryService.readActiveTaskMemories(task.getTaskId())
            .stream()
            .map(this::taskMemoryReference)
            .toList();
        var longTermMemoryReferences = longTermMemories.findAllByStatusOrderByCreatedAtAscMemoryIdAsc(MemoryStatus.ACTIVE)
            .stream()
            .filter(memory -> memoryReferencesTaskRun(memory, task.getTaskId(), executionIds, caseIds))
            .sorted(Comparator.comparing(LongTermMemory::getMemoryId))
            .map(this::longTermMemoryReference)
            .toList();
        var candidateResults = analysisCoverage.generatedCandidateResults();
        var rejectedCandidateNotes = candidateResults.stream()
            .filter(candidate -> Boolean.TRUE.equals(candidate.get("attempted")))
            .filter(candidate -> !Boolean.TRUE.equals(candidate.get("accepted")))
            .filter(candidate -> clean(stringValue(candidate.get("rejectionReason"))) != null)
            .map(candidate -> {
                var note = new LinkedHashMap<String, Object>();
                note.put("executionId", candidate.get("executionId"));
                note.put("rejectionReason", candidate.get("rejectionReason"));
                note.put("note", "Long-term memory candidate was rejected by the deterministic memory refinery.");
                return note;
            })
            .toList();

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("summary", "Task memory items=" + taskMemoryReferences.size()
            + ", acceptedLongTermMemories=" + longTermMemoryReferences.size()
            + ", generatedRejectedCandidates=" + rejectedCandidateNotes.size() + ".");
        metadata.put("taskMemoryCount", taskMemoryReferences.size());
        metadata.put("taskMemoryReferences", taskMemoryReferences);
        metadata.put("acceptedLongTermMemoryCount", longTermMemoryReferences.size());
        metadata.put("longTermMemoryReferences", longTermMemoryReferences);
        metadata.put("generatedCandidateAttemptCount", countCandidates(candidateResults, "attempted"));
        metadata.put("generatedAcceptedCandidateCount", countCandidates(candidateResults, "accepted"));
        metadata.put("generatedCreatedCandidateCount", countCandidates(candidateResults, "created"));
        metadata.put("generatedMergedCandidateCount", countCandidates(candidateResults, "merged"));
        metadata.put("generatedRejectedCandidateCount", rejectedCandidateNotes.size());
        metadata.put("generatedCandidateResults", candidateResults);
        metadata.put("rejectedCandidateNotes", rejectedCandidateNotes);
        metadata.put("traceReferences", Map.of(
            "executionIds", List.copyOf(executionIds),
            "caseIds", List.copyOf(caseIds),
            "taskMemoryIds", taskMemoryReferences.stream().map(reference -> stringValue(reference.get("memoryId"))).toList(),
            "longTermMemoryIds", longTermMemoryReferences.stream().map(reference -> stringValue(reference.get("memoryId"))).toList()
        ));
        return metadata;
    }

    private int countCandidates(List<Map<String, Object>> candidates, String field) {
        return (int) candidates.stream()
            .filter(candidate -> Boolean.TRUE.equals(candidate.get(field)))
            .count();
    }

    private Map<String, Object> taskMemoryReference(TaskMemoryView memory) {
        var reference = new LinkedHashMap<String, Object>();
        reference.put("memoryId", memory.memoryId());
        reference.put("summary", memory.summary());
        reference.put("scopeType", memory.scopeType().name());
        reference.put("sourceType", memory.sourceType().name());
        reference.put("sourceRef", memory.sourceRef());
        reference.put("confidence", memory.confidence());
        reference.put("lifecycleStage", memory.lifecycleStage());
        reference.put("tags", memory.tags());
        reference.put("metadata", limitedMemoryMetadata(memory.metadata()));
        return reference;
    }

    private Map<String, Object> longTermMemoryReference(LongTermMemory memory) {
        var reference = new LinkedHashMap<String, Object>();
        reference.put("memoryId", memory.getMemoryId());
        reference.put("summary", memory.getSummary());
        reference.put("scopeType", memory.getScopeType().name());
        reference.put("sourceType", memory.getSourceType().name());
        reference.put("sourceRef", memory.getSourceRef());
        reference.put("confidence", memory.getConfidence());
        reference.put("importance", memory.getImportance());
        reference.put("tags", memory.getTags());
        reference.put("metadata", limitedMemoryMetadata(memory.getMetadata()));
        return reference;
    }

    private Map<String, Object> limitedMemoryMetadata(Map<String, Object> metadata) {
        var limited = new LinkedHashMap<String, Object>();
        copyIfPresent(limited, metadata, "taskId");
        copyIfPresent(limited, metadata, "executionId");
        copyIfPresent(limited, metadata, "executionIds");
        copyIfPresent(limited, metadata, "caseId");
        copyIfPresent(limited, metadata, "caseIds");
        copyIfPresent(limited, metadata, "apiSpecId");
        copyIfPresent(limited, metadata, "classification");
        copyIfPresent(limited, metadata, "riskLevel");
        copyIfPresent(limited, metadata, "retryable");
        copyIfPresent(limited, metadata, "statusCode");
        copyIfPresent(limited, metadata, "errorCode");
        copyIfPresent(limited, metadata, "environment");
        copyIfPresent(limited, metadata, "occurrenceCount");
        copyIfPresent(limited, metadata, "mergeCount");
        return limited;
    }

    private boolean memoryReferencesTaskRun(
        LongTermMemory memory,
        String taskId,
        LinkedHashSet<String> executionIds,
        LinkedHashSet<String> caseIds
    ) {
        var metadata = memory.getMetadata() == null ? Map.<String, Object>of() : memory.getMetadata();
        if (taskId.equals(stringValue(metadata.get("taskId")))) {
            return true;
        }
        if (executionIds.contains(memory.getSourceRef())) {
            return true;
        }
        if (intersects(executionIds, stringList(metadata.get("executionIds")))) {
            return true;
        }
        return intersects(caseIds, stringList(metadata.get("caseIds")));
    }

    private boolean intersects(LinkedHashSet<String> values, List<String> candidates) {
        return candidates.stream().anyMatch(values::contains);
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

    private Map<String, Object> suiteCoverage(List<ExecutionRecord> records) {
        var firstFailingSteps = new ArrayList<Map<String, Object>>();
        var dependentSkippedSteps = new ArrayList<Map<String, Object>>();
        var suiteExecutionCount = 0;
        for (var record : records) {
            var suiteImpact = suiteExecutionImpact(record);
            if (!suiteImpact.suiteExecution()) {
                continue;
            }
            suiteExecutionCount++;
            if (!suiteImpact.firstFailingStep().isEmpty()) {
                var firstFailingStep = new LinkedHashMap<String, Object>();
                firstFailingStep.put("executionId", record.getExecutionId());
                firstFailingStep.put("caseId", record.getCaseId());
                firstFailingStep.putAll(suiteImpact.firstFailingStep());
                firstFailingSteps.add(firstFailingStep);
            }
            for (var step : suiteImpact.dependentSkippedSteps()) {
                var dependentStep = new LinkedHashMap<String, Object>();
                dependentStep.put("executionId", record.getExecutionId());
                dependentStep.put("caseId", record.getCaseId());
                dependentStep.putAll(step);
                dependentSkippedSteps.add(dependentStep);
            }
        }

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("suiteExecutionCount", suiteExecutionCount);
        metadata.put("suiteWithFirstFailingStepCount", firstFailingSteps.size());
        metadata.put("dependentSkippedStepCount", dependentSkippedSteps.size());
        metadata.put("firstFailingSteps", firstFailingSteps);
        metadata.put("dependentSkippedSteps", dependentSkippedSteps);
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

    private SuiteExecutionImpact suiteExecutionImpact(ExecutionRecord record) {
        var response = record.getResponseSnapshot() == null ? Map.<String, Object>of() : record.getResponseSnapshot();
        if (!Boolean.TRUE.equals(response.get("suite")) || !(response.get("steps") instanceof List<?> rawSteps)) {
            return SuiteExecutionImpact.none();
        }

        var steps = rawSteps.stream()
            .filter(Map.class::isInstance)
            .map(item -> objectMap((Map<?, ?>) item))
            .toList();
        var stepIds = steps.stream()
            .map(step -> clean(stringValue(step.get("stepId"))))
            .filter(value -> value != null)
            .toList();

        Map<String, Object> firstFailed = null;
        for (var step : steps) {
            if (failedSuiteStep(step)) {
                firstFailed = step;
                break;
            }
        }

        var dependentSkipped = new ArrayList<Map<String, Object>>();
        if (firstFailed != null) {
            var failedOrder = intValue(firstFailed.get("order"));
            for (var step : steps) {
                var stepOrder = intValue(step.get("order"));
                if (skippedSuiteStep(step)
                    && (failedOrder == null || stepOrder == null || stepOrder > failedOrder)
                    && contains(stringValue(step.get("message")), "prerequisite step failed")) {
                    dependentSkipped.add(suiteStepReference(step));
                }
            }
        }

        var firstFailingStep = firstFailed == null ? Map.<String, Object>of() : suiteStepReference(firstFailed);
        var failedStepId = clean(stringValue(firstFailingStep.get("stepId")));
        var failedStepOrder = intValue(firstFailingStep.get("order"));
        var failedStepApiSpecId = clean(stringValue(firstFailingStep.get("apiSpecId")));
        var impactSummary = suiteImpactSummary(steps.size(), firstFailingStep, dependentSkipped);
        return new SuiteExecutionImpact(
            true,
            steps.size(),
            stepIds,
            firstFailingStep,
            dependentSkipped,
            failedStepId,
            failedStepOrder,
            failedStepApiSpecId,
            impactSummary
        );
    }

    private String suiteImpactSummary(
        int stepCount,
        Map<String, Object> firstFailingStep,
        List<Map<String, Object>> dependentSkippedSteps
    ) {
        if (firstFailingStep.isEmpty()) {
            return "Suite execution has " + stepCount + " steps and no failed prerequisite step in the response snapshot.";
        }
        return "Suite first failing step " + firstFailingStep.get("stepId")
            + " order " + firstFailingStep.get("order")
            + " apiSpecId " + firstFailingStep.get("apiSpecId")
            + "; dependent skipped steps: " + dependentSkippedSteps.stream()
                .map(step -> stringValue(step.get("stepId")))
                .toList();
    }

    private Map<String, Object> suiteStepReference(Map<String, Object> step) {
        var reference = new LinkedHashMap<String, Object>();
        copyIfPresent(reference, step, "stepId");
        copyIfPresent(reference, step, "order");
        copyIfPresent(reference, step, "apiSpecId");
        copyIfPresent(reference, step, "targetApiSpecId");
        copyIfPresent(reference, step, "overallStatus");
        copyIfPresent(reference, step, "status");
        copyIfPresent(reference, step, "statusCode");
        copyIfPresent(reference, step, "message");
        return reference;
    }

    private void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private boolean failedSuiteStep(Map<String, Object> step) {
        var status = stringValue(step.get("overallStatus"));
        if (status == null) {
            status = stringValue(step.get("status"));
        }
        return OverallStatus.FAILED.name().equals(status)
            || OverallStatus.ERROR.name().equals(status)
            || OverallStatus.BLOCKED.name().equals(status);
    }

    private boolean skippedSuiteStep(Map<String, Object> step) {
        var status = stringValue(step.get("overallStatus"));
        if (status == null) {
            status = stringValue(step.get("status"));
        }
        return OverallStatus.SKIPPED.name().equals(status);
    }

    private Map<String, Object> objectMap(Map<?, ?> source) {
        var target = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> target.put(String.valueOf(key), value));
        return target;
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

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        return Integer.parseInt(value.toString());
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

    private record AnalysisCoverage(
        List<String> eligibleExecutionIds,
        List<String> existingAnalysisExecutionIds,
        List<String> generatedAnalysisExecutionIds,
        List<String> generatedObservationIds,
        List<String> missingAnalysisExecutionIds,
        List<String> ineligibleExecutionIds,
        List<Map<String, Object>> generatedCandidateResults
    ) {

        private Map<String, Object> toMetadata() {
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("mode", "BASIC");
            metadata.put("deepAnalysisRequested", false);
            metadata.put("eligibleExecutionCount", eligibleExecutionIds.size());
            metadata.put("existingBasicAnalysisCount", existingAnalysisExecutionIds.size());
            metadata.put("generatedBasicAnalysisCount", generatedAnalysisExecutionIds.size());
            metadata.put("missingBasicAnalysisCount", missingAnalysisExecutionIds.size());
            metadata.put("analysisComplete", missingAnalysisExecutionIds.isEmpty());
            metadata.put("staleAnalysis", false);
            metadata.put("eligibleExecutionIds", eligibleExecutionIds);
            metadata.put("existingAnalysisExecutionIds", existingAnalysisExecutionIds);
            metadata.put("generatedAnalysisExecutionIds", generatedAnalysisExecutionIds);
            metadata.put("generatedObservationIds", generatedObservationIds);
            metadata.put("missingAnalysisExecutionIds", missingAnalysisExecutionIds);
            metadata.put("ineligibleExecutionIds", ineligibleExecutionIds);
            metadata.put("generatedCandidateResults", generatedCandidateResults);
            return metadata;
        }
    }

    private record SuiteExecutionImpact(
        boolean suiteExecution,
        int totalSteps,
        List<String> stepIds,
        Map<String, Object> firstFailingStep,
        List<Map<String, Object>> dependentSkippedSteps,
        String failedStepId,
        Integer failedStepOrder,
        String failedStepApiSpecId,
        String impactSummary
    ) {

        private static SuiteExecutionImpact none() {
            return new SuiteExecutionImpact(false, 0, List.of(), Map.of(), List.of(), null, null, null, null);
        }

        private List<String> dependentSkippedStepIds() {
            return dependentSkippedSteps.stream()
                .map(step -> String.valueOf(step.get("stepId")))
                .toList();
        }
    }
}
