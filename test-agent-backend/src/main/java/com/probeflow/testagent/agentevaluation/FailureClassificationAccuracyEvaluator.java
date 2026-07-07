package com.probeflow.testagent.agentevaluation;

import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.ExecutorType;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.failureanalysis.FailureAnalysisApplicationService;
import com.probeflow.testagent.failureanalysis.FailureAnalysisRequest;
import com.probeflow.testagent.failureanalysis.FailureAnalysisResult;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FailureClassificationAccuracyEvaluator implements AgentEvaluationEvaluator {

    public static final String METRIC_NAME = "failure-classification";

    private final FailureAnalysisApplicationService failureAnalysis;
    private final ExecutionRecordRepository executionRecords;
    private final TaskRepository tasks;

    public FailureClassificationAccuracyEvaluator(
        FailureAnalysisApplicationService failureAnalysis,
        ExecutionRecordRepository executionRecords,
        TaskRepository tasks
    ) {
        this.failureAnalysis = failureAnalysis;
        this.executionRecords = executionRecords;
        this.tasks = tasks;
    }

    @Override
    public String capability() {
        return METRIC_NAME;
    }

    @Override
    public boolean supports(GoldenTaskFixture fixture) {
        return fixture.fixtureType() == EvaluationFixtureType.FAILURE_CLASSIFICATION;
    }

    @Override
    public EvaluationCaseResult evaluate(
        EvaluationDataset dataset,
        GoldenTaskFixture fixture,
        EvaluationRunContext context
    ) {
        var executionId = seedExecutionFacts(fixture, context);
        var result = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(executionId));
        var expected = fixture.expectedResults();
        var actual = actual(result);
        var diagnostics = diagnostics(expected, result);
        var score = score(expected, diagnostics);
        var passed = diagnostics.isEmpty() && score >= dataset.thresholdFor(METRIC_NAME);
        var metric = new EvaluationMetricResult(
            METRIC_NAME,
            score,
            dataset.thresholdFor(METRIC_NAME),
            dataset.weightFor(METRIC_NAME),
            passed,
            actual,
            expected,
            passed
                ? "FailureAnalysis classification, risk, suggestion and memory candidate matched fixture expectations."
                : String.join("; ", diagnostics)
        );

        if (passed) {
            return EvaluationCaseResult.passed(
                fixture.fixtureId(),
                fixture.capabilityTags(),
                "FailureAnalysis returned " + result.classification() + " at risk " + result.riskLevel() + ".",
                expected.toString(),
                List.of(metric)
            );
        }
        return EvaluationCaseResult.failed(
            fixture.fixtureId(),
            fixture.capabilityTags(),
            "FailureAnalysis returned unexpected classification or recovery guidance.",
            expected.toString(),
            diagnostics,
            List.of(metric)
        );
    }

    private String seedExecutionFacts(GoldenTaskFixture fixture, EvaluationRunContext context) {
        var setup = fixture.setupMetadata();
        var taskId = id(context, fixture, "task");
        if (!tasks.existsById(taskId)) {
            tasks.save(newTask(taskId, fixture));
        }

        var executionId = id(context, fixture, "execution");
        if (executionRecords.existsById(executionId)) {
            return executionId;
        }

        var response = new LinkedHashMap<String, Object>();
        response.putAll(map(setup.get("responseSnapshot")));
        var statusCode = integer(setup.get("statusCode"), integer(response.get("statusCode"), null));
        if (statusCode != null) {
            response.put("statusCode", statusCode);
        }
        response.putIfAbsent("failureType", "ASSERTION_FAILURE");
        response.putIfAbsent("bodyType", "json");

        var request = new LinkedHashMap<String, Object>();
        request.put("method", text(setup, "method", "POST"));
        request.put("path", text(setup, "requestPath", "/api/orders"));
        request.put("url", "https://api.test.example" + request.get("path"));
        request.put("headers", Map.of("Authorization", "[REDACTED]"));
        request.putAll(map(setup.get("requestSnapshot")));

        var record = new ExecutionRecord();
        record.setExecutionId(executionId);
        record.setTaskId(taskId);
        record.setCaseId(id(context, fixture, "case"));
        record.setStepId(id(context, fixture, "step"));
        record.setExecutorType(ExecutorType.HTTP);
        record.setEnvironment(text(setup, "environment", "phase8-evaluation"));
        record.setRequestSnapshot(request);
        record.setResponseSnapshot(response);
        record.setAssertionResults(assertions(setup.get("assertionResults")));
        record.setOverallStatus(OverallStatus.valueOf(text(setup, "overallStatus", "FAILED")));
        record.setCriticalFailed(bool(setup.get("criticalFailed"), false));
        record.setDurationMs(longValue(setup.get("durationMs"), 123L));
        record.setStatusCode(statusCode);
        record.setErrorMessage(text(setup, "errorMessage", null));
        return executionRecords.save(record).getExecutionId();
    }

    private Task newTask(String taskId, GoldenTaskFixture fixture) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Phase 8 failure classification evaluation " + fixture.fixtureId());
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("phase8-agent-evaluation");
        task.setTargetApiSpecIds(List.of());
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.PENDING);
        task.setPriority(TaskPriority.HIGH);
        task.setCreator("phase8-agent-evaluation");
        task.setMetadata(Map.of("fixtureId", fixture.fixtureId(), "capability", METRIC_NAME));
        return task;
    }

    private Map<String, Object> actual(FailureAnalysisResult result) {
        var actual = new LinkedHashMap<String, Object>();
        actual.put("classification", result.classification().name());
        actual.put("failureReason", result.failureReason());
        actual.put("riskLevel", result.riskLevel());
        actual.put("nextSuggestion", result.nextSuggestion());
        actual.put("retryable", result.retryable());
        actual.put("memoryCandidateAttempted", result.memoryCandidate().attempted());
        actual.put("memoryCandidatePresent", memoryCandidatePresent(result));
        actual.put("memoryCandidateCreated", result.memoryCandidate().created());
        actual.put("memoryCandidateMerged", result.memoryCandidate().merged());
        actual.put("memoryCandidateId", result.memoryCandidate().memoryId());
        actual.put("evidence", result.evidence());
        return actual;
    }

    private List<String> diagnostics(Map<String, Object> expected, FailureAnalysisResult result) {
        var diagnostics = new ArrayList<String>();
        var expectedClassification = text(expected, "classification", text(expected, "failureClassification", null));
        if (expectedClassification != null && !result.classification().name().equals(expectedClassification)) {
            diagnostics.add(
                "reason mismatch: expected " + expectedClassification + " but was " + result.classification().name()
            );
        }
        var expectedReason = text(expected, "failureReason", text(expected, "failureReasonContains", null));
        if (expectedReason != null && !contains(result.failureReason(), expectedReason)) {
            diagnostics.add(
                "reason mismatch: expected failure reason containing " + expectedReason + " but was " + result.failureReason()
            );
        }
        var expectedRisk = text(expected, "riskLevel", null);
        if (expectedRisk != null && !result.riskLevel().equals(expectedRisk)) {
            diagnostics.add(riskDiagnostic(expectedRisk, result.riskLevel()));
        }
        var expectedSuggestion = text(expected, "nextSuggestion", text(expected, "nextSuggestionContains", null));
        if (expectedSuggestion != null && !contains(result.nextSuggestion(), expectedSuggestion)) {
            diagnostics.add(
                "suggestion mismatch: expected next suggestion containing "
                    + expectedSuggestion + " but was " + result.nextSuggestion()
            );
        }
        if (expected.containsKey("memoryCandidatePresent")) {
            var expectedCandidate = bool(expected.get("memoryCandidatePresent"), false);
            var actualCandidate = memoryCandidatePresent(result);
            if (expectedCandidate && !actualCandidate) {
                diagnostics.add("missing candidate: expected reusable memory candidate but none was accepted or merged");
            } else if (!expectedCandidate && actualCandidate) {
                diagnostics.add("unexpected candidate: reusable memory candidate was accepted unexpectedly");
            }
        }
        return diagnostics;
    }

    private String riskDiagnostic(String expectedRisk, String actualRisk) {
        var expectedRank = riskRank(expectedRisk);
        var actualRank = riskRank(actualRisk);
        var direction = actualRank < expectedRank ? "underestimated" : "overestimated";
        return "risk mismatch: " + direction + " expected " + expectedRisk + " but was " + actualRisk;
    }

    private boolean memoryCandidatePresent(FailureAnalysisResult result) {
        return result.memoryCandidate().accepted()
            || result.memoryCandidate().created()
            || result.memoryCandidate().merged()
            || (result.memoryCandidate().memoryId() != null && !result.memoryCandidate().memoryId().isBlank());
    }

    private double score(Map<String, Object> expected, List<String> diagnostics) {
        var checks = 0;
        checks += expected.containsKey("classification") || expected.containsKey("failureClassification") ? 1 : 0;
        checks += expected.containsKey("failureReason") || expected.containsKey("failureReasonContains") ? 1 : 0;
        checks += expected.containsKey("riskLevel") ? 1 : 0;
        checks += expected.containsKey("nextSuggestion") || expected.containsKey("nextSuggestionContains") ? 1 : 0;
        checks += expected.containsKey("memoryCandidatePresent") ? 1 : 0;
        if (checks == 0) {
            return diagnostics.isEmpty() ? 1.0d : 0.0d;
        }
        return Math.max(0.0d, Math.round(((checks - diagnostics.size()) / (double) checks) * 10000.0d) / 10000.0d);
    }

    private String id(EvaluationRunContext context, GoldenTaskFixture fixture, String suffix) {
        var raw = context.fixtureNamespace() + ":" + fixture.fixtureId() + ":" + suffix;
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean contains(String text, String expected) {
        if (text == null || expected == null) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private int riskRank(String riskLevel) {
        if ("CRITICAL".equals(riskLevel)) {
            return 4;
        }
        if ("HIGH".equals(riskLevel)) {
            return 3;
        }
        if ("MEDIUM".equals(riskLevel)) {
            return 2;
        }
        return 1;
    }

    private String text(Map<String, Object> values, String key, String fallback) {
        var value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private Integer integer(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private Long longValue(Object value, Long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        var map = new LinkedHashMap<String, Object>();
        source.forEach((key, item) -> {
            if (key != null) {
                map.put(String.valueOf(key), item);
            }
        });
        return map;
    }

    private List<Map<String, Object>> assertions(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        var assertions = new ArrayList<Map<String, Object>>();
        for (var item : iterable) {
            if (item instanceof Map<?, ?> assertion) {
                assertions.add(map(assertion));
            }
        }
        return assertions;
    }
}
