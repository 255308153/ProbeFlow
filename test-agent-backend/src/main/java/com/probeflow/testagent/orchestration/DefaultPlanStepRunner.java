package com.probeflow.testagent.orchestration;

import com.probeflow.testagent.analysis.ApiAnalysisApplicationService;
import com.probeflow.testagent.analysis.ApiAnalysisRequest;
import com.probeflow.testagent.failureanalysis.FailureAnalysisApplicationService;
import com.probeflow.testagent.failureanalysis.TaskFailureAnalysisRequest;
import com.probeflow.testagent.httpexecution.HttpExecutionApplicationService;
import com.probeflow.testagent.httpexecution.HttpExecutionOptions;
import com.probeflow.testagent.httpexecution.HttpExecutionRequest;
import com.probeflow.testagent.knowledge.KnowledgeQuery;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalApplicationService;
import com.probeflow.testagent.report.ReportGenerationApplicationService;
import com.probeflow.testagent.report.ReportGenerationRequest;
import com.probeflow.testagent.task.PlanStep;
import com.probeflow.testagent.task.PlanStepType;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationApplicationService;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationMode;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationRequest;
import com.probeflow.testagent.testcasegeneration.TestCasePromotionRequest;
import com.probeflow.testagent.testcasegeneration.TestCasePromotionService;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DefaultPlanStepRunner implements PlanStepRunner {

    private final Map<PlanStepType, BiFunction<Task, PlanStep, StepOutcome>> routes;

    public DefaultPlanStepRunner(
        ApiAnalysisApplicationService apiAnalysis,
        KnowledgeRetrievalApplicationService knowledgeRetrieval,
        TestCaseGenerationApplicationService testCaseGeneration,
        TestCasePromotionService testCasePromotion,
        HttpExecutionApplicationService httpExecution,
        FailureAnalysisApplicationService failureAnalysis,
        ReportGenerationApplicationService reportGeneration
    ) {
        var configuredRoutes = new EnumMap<PlanStepType, BiFunction<Task, PlanStep, StepOutcome>>(PlanStepType.class);
        configuredRoutes.put(PlanStepType.ANALYZE_CODE_API, (task, step) -> analyzeApi(task, apiAnalysis));
        configuredRoutes.put(PlanStepType.RETRIEVE_KNOWLEDGE, (task, step) -> retrieveKnowledge(task, knowledgeRetrieval));
        configuredRoutes.put(PlanStepType.GENERATE_CASES, (task, step) -> generateCases(task, testCaseGeneration, testCasePromotion));
        configuredRoutes.put(PlanStepType.EXECUTE_SINGLE, (task, step) -> executeHttp(task, ExecutionMode.SINGLE, httpExecution));
        configuredRoutes.put(PlanStepType.EXECUTE_BATCH, (task, step) -> executeHttp(task, ExecutionMode.BATCH, httpExecution));
        configuredRoutes.put(PlanStepType.EXECUTE_SUITE, (task, step) -> executeHttp(task, ExecutionMode.SUITE_STEP, httpExecution));
        configuredRoutes.put(PlanStepType.ANALYZE_FAILURE, (task, step) -> analyzeFailures(task, failureAnalysis));
        configuredRoutes.put(PlanStepType.GENERATE_REPORT, (task, step) -> generateReport(task, reportGeneration));
        configuredRoutes.put(PlanStepType.REFINE_MEMORY, (task, step) -> StepOutcome.succeeded("Memory refinement delegated to existing feedback services", List.of()));
        this.routes = Map.copyOf(configuredRoutes);
    }

    @Override
    public StepOutcome run(Task task, PlanStep step) {
        var route = routes.get(step.getStepType());
        if (route == null) {
            return StepOutcome.failed("Unsupported PlanStepType: " + step.getStepType());
        }
        return route.apply(task, step);
    }

    private StepOutcome analyzeApi(Task task, ApiAnalysisApplicationService apiAnalysis) {
        if (!StringUtils.hasText(task.getSourceRef())) {
            return StepOutcome.failed("Task sourceRef is required for API analysis");
        }
        var result = apiAnalysis.analyze(ApiAnalysisRequest.existingMaterial(task.getSourceRef(), task.getCreator()));
        if (!result.succeeded()) {
            return StepOutcome.failed("API analysis failed: " + result.errorMessage());
        }
        return StepOutcome.succeeded(
            "API analysis succeeded; apiSpecs=" + result.apiSpecIds().size(),
            result.apiSpecIds()
        );
    }

    private StepOutcome retrieveKnowledge(Task task, KnowledgeRetrievalApplicationService knowledgeRetrieval) {
        var query = new KnowledgeQuery(
            "Task " + task.getTaskName(),
            null,
            null,
            null,
            null,
            null,
            null,
            "test_generation",
            List.of(),
            5,
            null
        );
        var targets = task.getTargetApiSpecIds() == null ? List.<String>of() : task.getTargetApiSpecIds();
        if (targets.isEmpty()) {
            var result = knowledgeRetrieval.retrieve(query);
            return StepOutcome.succeeded(
                "Knowledge retrieval completed; apiTargets=0 hits=" + result.hits().size() + " lowConfidence=" + result.lowConfidence(),
                List.of()
            );
        }

        var totalHits = 0;
        var lowConfidence = false;
        for (var apiSpecId : targets) {
            var result = knowledgeRetrieval.retrieveForApiSpec(apiSpecId, query);
            totalHits += result.hits().size();
            lowConfidence = lowConfidence || result.lowConfidence();
        }
        return StepOutcome.succeeded(
            "Knowledge retrieval completed; apiTargets=" + targets.size() + " hits=" + totalHits + " lowConfidence=" + lowConfidence,
            List.of()
        );
    }

    private StepOutcome generateCases(
        Task task,
        TestCaseGenerationApplicationService testCaseGeneration,
        TestCasePromotionService testCasePromotion
    ) {
        var targets = task.getTargetApiSpecIds() == null ? List.<String>of() : task.getTargetApiSpecIds();
        if (targets.isEmpty()) {
            return StepOutcome.failed("targetApiSpecIds are required for test case generation");
        }
        var result = testCaseGeneration.generate(new TestCaseGenerationRequest(
            task.getTaskId(),
            stringMetadata(task, "sessionId", task.getTaskId()),
            targets,
            generationMode(targets),
            List.of(),
            integerMetadata(task, "tokenBudget")
        ));
        if (task.getPromotionMode() == PromotionMode.AUTO && !result.createdDraftIds().isEmpty()) {
            var promotion = testCasePromotion.promote(new TestCasePromotionRequest(result.createdDraftIds(), task.getCreator()));
            putMetadata(task, "selectedCaseIds", promotion.promotedCaseIds());
            putMetadata(task, "promotedCaseIds", promotion.promotedCaseIds());
            return StepOutcome.succeeded(
                "Test case generation completed; drafts=" + result.createdDraftIds().size()
                    + " promoted=" + promotion.promotedCaseIds().size()
                    + " warnings=" + result.warnings().size(),
                promotion.promotedCaseIds()
            );
        }
        if (task.getPromotionMode() == PromotionMode.MANUAL) {
            putMetadata(task, "createdDraftIds", result.createdDraftIds());
            return StepOutcome.paused(
                com.probeflow.testagent.task.TaskStatus.WAITING_FOR_REVIEW,
                "Waiting for manual review of " + result.createdDraftIds().size() + " generated draft(s)"
            );
        }
        return StepOutcome.succeeded(
            "Test case generation completed; drafts=" + result.createdDraftIds().size() + " warnings=" + result.warnings().size(),
            result.createdDraftIds()
        );
    }

    private TestCaseGenerationMode generationMode(List<String> targetApiSpecIds) {
        return targetApiSpecIds.size() == 1 ? TestCaseGenerationMode.SINGLE : TestCaseGenerationMode.BATCH;
    }

    private StepOutcome executeHttp(Task task, ExecutionMode executionMode, HttpExecutionApplicationService httpExecution) {
        var readiness = executionReadiness(task);
        if (!readiness.ready()) {
            putMetadata(task, "executionReadinessStatus", "BLOCKED");
            putMetadata(task, "executionReadinessBlockers", readiness.structuredBlockers());
            putMetadata(task, "needsFailureAnalysis", false);
            putMetadata(task, "lastHttpExecutionCounts", emptyExecutionCounts());
            return StepOutcome.blocked(
                "HTTP execution blocked by readiness checks; blockers=" + readiness.blockers().size(),
                readiness.blockerDetails()
            );
        }
        putMetadata(task, "executionReadinessStatus", "READY");
        putMetadata(task, "executionReadinessBlockers", List.of());
        var result = httpExecution.execute(new HttpExecutionRequest(
            task.getTaskId(),
            selectedCaseIds(task),
            executionMode,
            stringMetadata(task, "environment", "default"),
            booleanMetadata(task, "dryRun", false),
            HttpExecutionOptions.defaults(),
            mapMetadata(task, "environmentVariables"),
            mapMetadata(task, "authVariables")
        ));
        var counts = result.counts();
        var executionRefs = result.caseResults().stream()
            .map(caseResult -> caseResult.executionRecordId())
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        putMetadata(task, "executionIds", executionRefs);
        putMetadata(task, "lastHttpExecutionCounts", Map.of(
            "total", counts.total(),
            "passed", counts.passed(),
            "failed", counts.failed(),
            "error", counts.error(),
            "blocked", counts.blocked(),
            "skipped", counts.skipped()
        ));
        putMetadata(task, "needsFailureAnalysis", needsFailureAnalysis(task, counts));
        return StepOutcome.succeeded(
            "HTTP execution completed; total=" + counts.total()
                + " passed=" + counts.passed()
                + " failed=" + counts.failed()
                + " error=" + counts.error()
                + " blocked=" + counts.blocked()
                + " skipped=" + counts.skipped(),
            executionRefs
        );
    }

    private ExecutionReadiness executionReadiness(Task task) {
        var blockers = new ArrayList<ExecutionReadinessBlocker>();
        if (selectedCaseIds(task).isEmpty()) {
            blockers.add(new ExecutionReadinessBlocker(
                "MISSING_SELECTED_CASES",
                "selectedCaseIds",
                "task metadata selectedCaseIds is required before HTTP execution"
            ));
        }
        if (!hasTextMetadata(task, "environment")) {
            blockers.add(new ExecutionReadinessBlocker(
                "MISSING_ENVIRONMENT",
                "environment",
                "task metadata environment is required before HTTP execution"
            ));
        }
        if (!StringUtils.hasText(firstPresent(mapMetadata(task, "environmentVariables"), "baseUrl", "base_url", "BASE_URL"))) {
            blockers.add(new ExecutionReadinessBlocker(
                "MISSING_BASE_URL",
                "environmentVariables.baseUrl",
                "task metadata environmentVariables.baseUrl is required before HTTP execution"
            ));
        }
        if ((booleanMetadata(task, "authRequired", false) || booleanMetadata(task, "requiresAuth", false))
            && !hasAnyValue(mapMetadata(task, "authVariables"))) {
            blockers.add(new ExecutionReadinessBlocker(
                "MISSING_AUTH",
                "authVariables",
                "task metadata authVariables must contain credentials when authRequired=true"
            ));
        }
        return new ExecutionReadiness(blockers);
    }

    private Map<String, Object> emptyExecutionCounts() {
        return Map.of(
            "total", 0,
            "passed", 0,
            "failed", 0,
            "error", 0,
            "blocked", 0,
            "skipped", 0
        );
    }

    private StepOutcome analyzeFailures(Task task, FailureAnalysisApplicationService failureAnalysis) {
        if (!booleanMetadata(task, "needsFailureAnalysis", true)) {
            return StepOutcome.skippedNonBlocking("No failed, errored, blocked or warning executions need analysis");
        }
        var result = failureAnalysis.analyzeTask(TaskFailureAnalysisRequest.basic(task.getTaskId(), executionIds(task)));
        var observationIds = result.executionResults().stream()
            .flatMap(executionResult -> executionResult.observationIds().stream())
            .distinct()
            .toList();
        return StepOutcome.succeeded(
            "Failure analysis completed; executions=" + result.counts().totalExecutions()
                + " groupedFailures=" + result.groupedFailures().size(),
            observationIds
        );
    }

    private StepOutcome generateReport(Task task, ReportGenerationApplicationService reportGeneration) {
        var result = reportGeneration.generateTaskReport(ReportGenerationRequest.forTask(task.getTaskId()));
        return StepOutcome.completed(
            "Report generated; state=" + result.state()
                + " cases=" + result.caseCount()
                + " executions=" + result.executionCount(),
            List.of(result.reportId())
        );
    }

    private List<String> selectedCaseIds(Task task) {
        return stringListMetadata(task, "selectedCaseIds");
    }

    private List<String> executionIds(Task task) {
        return stringListMetadata(task, "executionIds");
    }

    private boolean needsFailureAnalysis(Task task, com.probeflow.testagent.httpexecution.HttpExecutionCounts counts) {
        return counts.failed() > 0
            || counts.error() > 0
            || counts.blocked() > 0
            || booleanMetadata(task, "executionHasWarnings", false);
    }

    private List<String> stringListMetadata(Task task, String key) {
        var value = task.getMetadata() == null ? null : task.getMetadata().get(key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .map(item -> item == null ? "" : item.toString())
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private String stringMetadata(Task task, String key, String defaultValue) {
        var value = task.getMetadata() == null ? null : task.getMetadata().get(key);
        return value == null || !StringUtils.hasText(value.toString()) ? defaultValue : value.toString();
    }

    private boolean hasTextMetadata(Task task, String key) {
        var value = task.getMetadata() == null ? null : task.getMetadata().get(key);
        return value != null && StringUtils.hasText(value.toString());
    }

    private Boolean booleanMetadata(Task task, String key, boolean defaultValue) {
        var value = task.getMetadata() == null ? null : task.getMetadata().get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
    }

    private Integer integerMetadata(Task task, String key) {
        var value = task.getMetadata() == null ? null : task.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && StringUtils.hasText(value.toString())) {
            return Integer.parseInt(value.toString());
        }
        return null;
    }

    private Map<String, Object> mapMetadata(Task task, String key) {
        var value = task.getMetadata() == null ? null : task.getMetadata().get(key);
        if (!(value instanceof Map<?, ?> values)) {
            return Map.of();
        }
        var result = new ArrayList<Map.Entry<String, Object>>();
        values.forEach((entryKey, entryValue) -> {
            if (entryKey != null && StringUtils.hasText(entryKey.toString())) {
                result.add(Map.entry(entryKey.toString(), entryValue));
            }
        });
        return Map.ofEntries(result.toArray(Map.Entry[]::new));
    }

    private String firstPresent(Map<String, Object> values, String... keys) {
        for (var key : keys) {
            var value = values.get(key);
            if (value != null && StringUtils.hasText(value.toString())) {
                return value.toString();
            }
        }
        return null;
    }

    private boolean hasAnyValue(Map<String, Object> values) {
        return values.values().stream()
            .anyMatch(value -> value != null && StringUtils.hasText(value.toString()));
    }

    private void putMetadata(Task task, String key, Object value) {
        var metadata = task.getMetadata() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<>(task.getMetadata());
        metadata.put(key, value);
        task.setMetadata(metadata);
    }

    private record ExecutionReadiness(List<ExecutionReadinessBlocker> blockers) {
        private boolean ready() {
            return blockers.isEmpty();
        }

        private List<String> blockerDetails() {
            return blockers.stream()
                .map(ExecutionReadinessBlocker::detail)
                .toList();
        }

        private List<Map<String, Object>> structuredBlockers() {
            return blockers.stream()
                .map(ExecutionReadinessBlocker::structured)
                .toList();
        }
    }

    private record ExecutionReadinessBlocker(String code, String field, String message) {
        private String detail() {
            return code + ": " + message;
        }

        private Map<String, Object> structured() {
            return Map.of(
                "code", code,
                "field", field,
                "message", message
            );
        }
    }
}
