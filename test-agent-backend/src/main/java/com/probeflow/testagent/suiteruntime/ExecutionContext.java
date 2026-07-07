package com.probeflow.testagent.suiteruntime;

import com.probeflow.testagent.httpexecution.HttpExecutionRequest;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.testcase.TestCase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExecutionContext {

    private final Map<String, Object> env = new LinkedHashMap<>();
    private final Map<String, Object> task = new LinkedHashMap<>();
    private final Map<String, Object> suite = new LinkedHashMap<>();
    private final Map<String, Object> caseScope = new LinkedHashMap<>();
    private final Map<String, Object> fn = new LinkedHashMap<>();
    private final Map<String, Object> data = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> step = new LinkedHashMap<>();
    private final List<Map<String, Object>> auditEvents = new ArrayList<>();
    private final List<Map<String, Object>> diagnostics = new ArrayList<>();

    public static ExecutionContext create(Task task, TestCase testCase, HttpExecutionRequest request) {
        var context = new ExecutionContext();
        context.env.putAll(request.environmentVariables());
        context.env.putAll(request.authVariables());
        context.task.put("taskId", task.getTaskId());
        context.task.put("taskName", task.getTaskName());
        context.task.put("taskType", task.getTaskType() == null ? null : task.getTaskType().name());
        context.task.put("status", task.getStatus() == null ? null : task.getStatus().name());
        context.task.put("sourceType", task.getSourceType() == null ? null : task.getSourceType().name());
        context.task.put("sourceRef", task.getSourceRef());
        context.task.put("targetApiSpecIds", task.getTargetApiSpecIds());
        context.task.put("metadata", task.getMetadata() == null ? Map.of() : new LinkedHashMap<>(task.getMetadata()));
        if (task.getMetadata() != null) {
            context.task.putAll(task.getMetadata());
        }
        context.caseScope.put("caseId", testCase.getCaseId());
        context.caseScope.put("title", testCase.getTitle());
        context.caseScope.put("scenarioName", testCase.getScenarioName());
        context.caseScope.put("moduleName", testCase.getModuleName());
        context.caseScope.put("detail", testCase.getDetail() == null ? Map.of() : new LinkedHashMap<>(testCase.getDetail()));
        if (testCase.getDetail() != null) {
            context.caseScope.putAll(testCase.getDetail());
        }
        context.fn.put("provider", "controlled");
        context.data.put("provider", "controlled");
        return context;
    }

    public Object scopeValue(String scope) {
        return switch (scope) {
            case "env" -> env;
            case "task" -> task;
            case "suite" -> suite;
            case "case" -> caseScope;
            case "step" -> step;
            case "fn" -> fn;
            case "data" -> data;
            default -> null;
        };
    }

    public boolean hasScope(String scope) {
        return scopeValue(scope) != null;
    }

    public boolean contains(String scope, String path) {
        return PathAccess.resolve(scopeValue(scope), path).found();
    }

    public Object read(String scope, String path) {
        return PathAccess.resolve(scopeValue(scope), path).value();
    }

    public Object writeSuite(String key, Object value) {
        return suite.put(key, value);
    }

    public Object writeStep(String stepId, String key, Object value) {
        var values = step.computeIfAbsent(stepId, ignored -> new LinkedHashMap<>());
        return values.put(key, value);
    }

    public Map<String, Object> summary() {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("env", redactMap(env));
        summary.put("task", redactMap(task));
        summary.put("suite", redactMap(suite));
        summary.put("case", redactMap(caseScope));
        summary.put("step", redactMap(step));
        summary.put("fn", redactMap(fn));
        summary.put("data", redactMap(data));
        return summary;
    }

    public List<Map<String, Object>> auditEvents() {
        return List.copyOf(auditEvents);
    }

    public void addAuditEvent(Map<String, Object> event) {
        var copy = new LinkedHashMap<>(event);
        copy.putIfAbsent("occurredAt", Instant.EPOCH.toString());
        copy.putIfAbsent("sequence", auditEvents.size() + 1);
        auditEvents.add(copy);
    }

    public List<Map<String, Object>> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public void addDiagnostic(RuntimeDiagnostic diagnostic) {
        diagnostics.add(diagnostic.toMap());
    }

    public void addDiagnostic(Map<String, Object> diagnostic) {
        diagnostics.add(new LinkedHashMap<>(diagnostic));
    }

    public Map<String, Object> variableAuditSummary() {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("totalEvents", auditEvents.size());
        summary.put("consumptionEvents", countEvents("CONSUMPTION"));
        summary.put("productionEvents", countEvents("PRODUCTION"));
        summary.put("failureEvents", auditEvents.stream().filter(event -> Boolean.FALSE.equals(event.get("resolved"))
            || Boolean.FALSE.equals(event.get("success"))).count());
        summary.put("events", auditEvents());
        return summary;
    }

    private long countEvents(String eventType) {
        return auditEvents.stream()
            .filter(event -> eventType.equals(event.get("eventType")))
            .count();
    }

    private Object redactMap(Object value) {
        return RuntimeRedactor.redact(value, "");
    }
}
