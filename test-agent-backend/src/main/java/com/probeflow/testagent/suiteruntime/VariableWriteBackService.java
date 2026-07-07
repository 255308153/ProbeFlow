package com.probeflow.testagent.suiteruntime;

import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class VariableWriteBackService {

    public boolean write(ExecutionContext context, String stepId, List<ExtractedVariable> extractedVariables) {
        var blockingFailure = false;
        for (var extracted : extractedVariables) {
            if (!extracted.success()) {
                blockingFailure = blockingFailure || extracted.blockingFailure();
                context.addDiagnostic(extracted.diagnostic());
                auditFailure(context, extracted);
                continue;
            }
            if ("suite".equals(extracted.targetScope())) {
                var overwritten = context.contains("suite", extracted.targetKey());
                var oldValue = overwritten ? context.read("suite", extracted.targetKey()) : null;
                context.writeSuite(extracted.targetKey(), extracted.value());
                auditProduction(context, extracted, overwritten, oldValue);
            } else if ("step".equals(extracted.targetScope())) {
                var stepPath = stepId + "." + extracted.targetKey();
                var overwritten = context.contains("step", stepPath);
                var oldValue = overwritten ? context.read("step", stepPath) : null;
                context.writeStep(stepId, extracted.targetKey(), extracted.value());
                auditProduction(context, extracted, overwritten, oldValue);
            } else {
                blockingFailure = true;
                var diagnostic = new LinkedHashMap<String, Object>();
                diagnostic.put("code", "UNSUPPORTED_WRITE_SCOPE");
                diagnostic.put("message", "Unsupported variable write scope: " + extracted.targetScope());
                diagnostic.put("stepId", stepId);
                diagnostic.put("targetScope", extracted.targetScope());
                diagnostic.put("targetKey", extracted.targetKey());
                context.addDiagnostic(diagnostic);
                auditFailure(context, extracted, diagnostic, true);
            }
        }
        return blockingFailure;
    }

    private void auditProduction(
        ExecutionContext context,
        ExtractedVariable extracted,
        boolean overwritten,
        Object oldValue
    ) {
        var event = new LinkedHashMap<String, Object>();
        event.put("eventType", "PRODUCTION");
        event.put("stepId", extracted.stepId());
        event.put("sourceType", extracted.sourceType());
        event.put("sourcePath", extracted.sourcePath());
        event.put("targetScope", extracted.targetScope());
        event.put("targetKey", extracted.targetKey());
        event.put("success", true);
        event.put("overwritten", overwritten);
        event.put("required", extracted.required());
        event.put("failureStrategy", extracted.failureStrategy());
        event.put("fallbackApplied", extracted.fallbackApplied());
        event.put("valueOrigin", extracted.fallbackApplied() ? extracted.failureStrategy() : "EXTRACTED");
        if (overwritten) {
            event.put("oldValueSummary", RuntimeRedactor.valueSummary(extracted.targetKey(), oldValue));
        }
        event.put("newValueSummary", RuntimeRedactor.valueSummary(extracted.targetKey(), extracted.value()));
        context.addAuditEvent(event);
    }

    private void auditFailure(ExecutionContext context, ExtractedVariable extracted) {
        auditFailure(context, extracted, extracted.diagnostic(), extracted.blockingFailure());
    }

    private void auditFailure(
        ExecutionContext context,
        ExtractedVariable extracted,
        java.util.Map<String, Object> diagnostic,
        boolean blockingFailure
    ) {
        var event = new LinkedHashMap<String, Object>();
        event.put("eventType", "PRODUCTION");
        event.put("stepId", extracted.stepId());
        event.put("sourceType", extracted.sourceType());
        event.put("sourcePath", extracted.sourcePath());
        event.put("targetScope", extracted.targetScope());
        event.put("targetKey", extracted.targetKey());
        event.put("success", false);
        event.put("blockingFailure", blockingFailure);
        event.put("required", extracted.required());
        event.put("failureStrategy", extracted.failureStrategy());
        event.put("fallbackApplied", extracted.fallbackApplied());
        event.put("failureReason", diagnostic.get("code"));
        if (extracted.success()) {
            event.put("attemptedValueSummary", RuntimeRedactor.valueSummary(extracted.targetKey(), extracted.value()));
        }
        context.addAuditEvent(event);
    }
}
