package com.probeflow.testagent.suiteruntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class VariableResolver {

    private static final Pattern VARIABLE_EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern SIMPLE_REFERENCE = Pattern.compile("([A-Za-z][A-Za-z0-9_-]*)\\.([A-Za-z0-9_.\\[\\]-]+)");

    public VariableResolutionResult resolveRequestTemplate(
        ExecutionContext context,
        String stepId,
        Object template
    ) {
        var diagnostics = new ArrayList<Map<String, Object>>();
        var resolved = resolveValue(context, stepId, "request", template, diagnostics);
        diagnostics.forEach(context::addDiagnostic);
        return new VariableResolutionResult(resolved, diagnostics);
    }

    private Object resolveValue(
        ExecutionContext context,
        String stepId,
        String location,
        Object value,
        List<Map<String, Object>> diagnostics
    ) {
        if (value instanceof String text) {
            return resolveText(context, stepId, location, text, diagnostics);
        }
        if (value instanceof Map<?, ?> incoming) {
            var resolved = new LinkedHashMap<String, Object>();
            incoming.forEach((key, mapValue) -> {
                if (key != null) {
                    var childLocation = location + "." + key;
                    resolved.put(key.toString(), resolveValue(context, stepId, childLocation, mapValue, diagnostics));
                }
            });
            return resolved;
        }
        if (value instanceof List<?> incoming) {
            var resolved = new ArrayList<Object>();
            for (var index = 0; index < incoming.size(); index++) {
                resolved.add(resolveValue(context, stepId, location + "[" + index + "]", incoming.get(index), diagnostics));
            }
            return resolved;
        }
        return value;
    }

    private Object resolveText(
        ExecutionContext context,
        String stepId,
        String location,
        String text,
        List<Map<String, Object>> diagnostics
    ) {
        var matcher = VARIABLE_EXPRESSION.matcher(text);
        if (matcher.matches()) {
            return resolveExpression(context, stepId, location, matcher.group(0), matcher.group(1), diagnostics);
        }
        matcher = VARIABLE_EXPRESSION.matcher(text);
        var resolved = new StringBuffer();
        while (matcher.find()) {
            var value = resolveExpression(context, stepId, location, matcher.group(0), matcher.group(1), diagnostics);
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private Object resolveExpression(
        ExecutionContext context,
        String stepId,
        String location,
        String expression,
        String body,
        List<Map<String, Object>> diagnostics
    ) {
        var matcher = SIMPLE_REFERENCE.matcher(body);
        if (!matcher.matches()) {
            var diagnostic = diagnostic("INVALID_VARIABLE_EXPRESSION", "Unsupported variable expression: " + expression,
                stepId, location, null, null, expression);
            diagnostics.add(diagnostic);
            auditConsumption(context, stepId, location, expression, null, null, false, null, "INVALID_VARIABLE_EXPRESSION");
            return expression;
        }
        var scope = matcher.group(1);
        var path = matcher.group(2);
        if (!context.hasScope(scope) || "fn".equals(scope) || "data".equals(scope)) {
            var diagnostic = diagnostic("UNSUPPORTED_VARIABLE_SCOPE", "Unsupported variable scope: " + scope,
                stepId, location, scope, path, expression);
            diagnostics.add(diagnostic);
            auditConsumption(context, stepId, location, expression, scope, path, false, null, "UNSUPPORTED_VARIABLE_SCOPE");
            return expression;
        }
        var resolved = PathAccess.resolve(context.scopeValue(scope), path);
        if (!resolved.found()) {
            var diagnostic = diagnostic(resolved.failureCode(), "Unable to resolve variable: " + expression,
                stepId, location, scope, path, expression);
            diagnostics.add(diagnostic);
            auditConsumption(context, stepId, location, expression, scope, path, false, null, resolved.failureCode());
            return expression;
        }
        auditConsumption(context, stepId, location, expression, scope, path, true, resolved.value(), null);
        return resolved.value();
    }

    private void auditConsumption(
        ExecutionContext context,
        String stepId,
        String location,
        String expression,
        String scope,
        String path,
        boolean resolved,
        Object value,
        String failureReason
    ) {
        var event = new LinkedHashMap<String, Object>();
        event.put("eventType", "CONSUMPTION");
        event.put("stepId", stepId);
        event.put("location", location);
        event.put("expression", expression);
        event.put("scope", scope);
        event.put("path", path);
        event.put("resolved", resolved);
        event.put("redactedValueSummary", RuntimeRedactor.valueSummary(path, value));
        if (failureReason != null) {
            event.put("failureReason", failureReason);
        }
        context.addAuditEvent(event);
    }

    private Map<String, Object> diagnostic(
        String code,
        String message,
        String stepId,
        String location,
        String scope,
        String path,
        String expression
    ) {
        return new RuntimeDiagnostic(code, message, stepId, location, scope, path, expression).toMap();
    }
}
