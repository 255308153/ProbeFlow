package com.probeflow.testagent.report;

import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionRepository;
import com.probeflow.testagent.testcase.TestCase;
import com.probeflow.testagent.testcase.TestCaseRepository;
import java.util.ArrayList;
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
        var caseIds = caseIds(task, taskExecutions);
        var state = reportState(caseIds.size(), records.size());

        var report = new Report();
        report.setTaskId(task.getTaskId());
        report.setSummary(summary(task, state, caseIds.size(), records.size()));
        report.setCaseCount(caseIds.size());
        report.setPassCount(0);
        report.setFailCount(0);
        report.setWarningCount(0);
        report.setRiskSummary(riskSummary(state));
        report.setFindings(List.of(noResultFinding(state, caseIds.size(), records.size())));
        report.setSuggestions(List.of());
        report.setMetadata(metadata(task, caseIds, taskExecutions.size(), records, state));

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

    private List<String> caseIds(Task task, List<com.probeflow.testagent.taskcaseexecution.TaskCaseExecution> taskExecutions) {
        var caseIds = new LinkedHashSet<String>();
        var targetApiSpecIds = task.getTargetApiSpecIds() == null ? List.<String>of() : task.getTargetApiSpecIds();
        if (!targetApiSpecIds.isEmpty()) {
            testCases.findAllByPrimaryApiSpecIdInOrderByCreatedAtAscCaseIdAsc(targetApiSpecIds)
                .stream()
                .map(TestCase::getCaseId)
                .forEach(caseIds::add);
        }
        taskExecutions.stream()
            .map(com.probeflow.testagent.taskcaseexecution.TaskCaseExecution::getCaseId)
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

    private String summary(Task task, String state, int caseCount, int executionCount) {
        return "Task report snapshot for " + task.getTaskName()
            + " (" + task.getTaskId() + ") state=" + state
            + " cases=" + caseCount
            + " executions=" + executionCount + ".";
    }

    private String riskSummary(String state) {
        return switch (state) {
            case "NO_CASES" -> "No test cases are linked to this task yet.";
            case "NO_EXECUTIONS" -> "Test cases are available, but no executions have been recorded yet.";
            default -> "Execution records are available; detailed risk aggregation is reserved for later Phase 8 slices.";
        };
    }

    private Map<String, Object> noResultFinding(String state, int caseCount, int executionCount) {
        var finding = new LinkedHashMap<String, Object>();
        finding.put("type", "REPORT_STATE");
        finding.put("severity", "INFO");
        finding.put("state", state);
        finding.put("caseCount", caseCount);
        finding.put("executionCount", executionCount);
        finding.put("evidenceType", "FACTUAL");
        finding.put("message", switch (state) {
            case "NO_CASES" -> "No test cases are linked to this task.";
            case "NO_EXECUTIONS" -> "Task has linked test cases but no execution records.";
            default -> "Task has execution records available for later report aggregation.";
        });
        return finding;
    }

    private Map<String, Object> metadata(
        Task task,
        List<String> caseIds,
        int taskCaseExecutionCount,
        List<ExecutionRecord> records,
        String state
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
        metadata.put("taskCaseExecutionCount", taskCaseExecutionCount);
        metadata.put("task", taskMetadata(task));
        return metadata;
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

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
