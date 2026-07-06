package com.probeflow.testagent.report;

import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskRepository;
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
    private final TaskCaseExecutionRepository taskCaseExecutions;
    private final ExecutionRecordRepository executionRecords;
    private final ReportRepository reports;

    public ReportGenerationApplicationService(
        TaskRepository tasks,
        TestCaseRepository testCases,
        TaskCaseExecutionRepository taskCaseExecutions,
        ExecutionRecordRepository executionRecords,
        ReportRepository reports
    ) {
        this.tasks = tasks;
        this.testCases = testCases;
        this.taskCaseExecutions = taskCaseExecutions;
        this.executionRecords = executionRecords;
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
        report.setFindings(List.of(noResultFinding(state, caseIds.size(), records.size(), executionSummary)));
        report.setSuggestions(List.of());
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
}
