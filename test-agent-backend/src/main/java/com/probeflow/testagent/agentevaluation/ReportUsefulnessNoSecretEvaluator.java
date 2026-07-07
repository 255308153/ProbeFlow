package com.probeflow.testagent.agentevaluation;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.ExecutorType;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.report.Report;
import com.probeflow.testagent.report.ReportGenerationApplicationService;
import com.probeflow.testagent.report.ReportGenerationRequest;
import com.probeflow.testagent.report.ReportRepository;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcase.CaseCategory;
import com.probeflow.testagent.testcase.CasePriority;
import com.probeflow.testagent.testcase.CaseRiskLevel;
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcase.CaseStatus;
import com.probeflow.testagent.testcase.DetailType;
import com.probeflow.testagent.testcase.TestCase;
import com.probeflow.testagent.testcase.TestCaseMode;
import com.probeflow.testagent.testcase.TestCaseRepository;
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
public class ReportUsefulnessNoSecretEvaluator implements AgentEvaluationEvaluator {

    public static final String METRIC_NAME = "report-usefulness";

    private final ReportGenerationApplicationService reportGeneration;
    private final ReportRepository reports;
    private final TaskRepository tasks;
    private final ApiSpecRepository apiSpecs;
    private final TestCaseRepository testCases;
    private final ExecutionRecordRepository executionRecords;

    public ReportUsefulnessNoSecretEvaluator(
        ReportGenerationApplicationService reportGeneration,
        ReportRepository reports,
        TaskRepository tasks,
        ApiSpecRepository apiSpecs,
        TestCaseRepository testCases,
        ExecutionRecordRepository executionRecords
    ) {
        this.reportGeneration = reportGeneration;
        this.reports = reports;
        this.tasks = tasks;
        this.apiSpecs = apiSpecs;
        this.testCases = testCases;
        this.executionRecords = executionRecords;
    }

    @Override
    public String capability() {
        return METRIC_NAME;
    }

    @Override
    public boolean supports(GoldenTaskFixture fixture) {
        return fixture.fixtureType() == EvaluationFixtureType.REPORT_USEFULNESS;
    }

    @Override
    public EvaluationCaseResult evaluate(
        EvaluationDataset dataset,
        GoldenTaskFixture fixture,
        EvaluationRunContext context
    ) {
        var asset = reportAsset(fixture, context);
        var expected = fixture.expectedResults();
        var actual = actual(asset);
        var diagnostics = diagnostics(expected, asset);
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
                ? "Report sections, evidence, recommendations, references and no-secret checks matched fixture expectations."
                : String.join("; ", diagnostics)
        );

        if (passed) {
            return EvaluationCaseResult.passed(
                fixture.fixtureId(),
                fixture.capabilityTags(),
                "Report contained useful summary, evidence, recommendations and memory feedback without secret leakage.",
                expected.toString(),
                List.of(metric)
            );
        }
        return EvaluationCaseResult.failed(
            fixture.fixtureId(),
            fixture.capabilityTags(),
            "Report missed expected usefulness or no-secret checks.",
            expected.toString(),
            diagnostics,
            List.of(metric)
        );
    }

    private ReportAsset reportAsset(GoldenTaskFixture fixture, EvaluationRunContext context) {
        var configured = map(fixture.setupMetadata().get("report"));
        if (!configured.isEmpty()) {
            return assetFromMap(configured);
        }

        var taskId = seedReportFixture(fixture, context);
        var result = reportGeneration.generateTaskReport(ReportGenerationRequest.forTask(taskId));
        var report = reports.findById(result.reportId())
            .orElseThrow(() -> new IllegalStateException("Generated report not found: " + result.reportId()));
        return assetFromReport(report);
    }

    private String seedReportFixture(GoldenTaskFixture fixture, EvaluationRunContext context) {
        var taskId = id(context, fixture, "task");
        var apiSpecId = id(context, fixture, "api-report");
        var caseId = id(context, fixture, "case");
        var executionId = id(context, fixture, "execution");
        if (!apiSpecs.existsById(apiSpecId)) {
            apiSpecs.save(newApiSpec(apiSpecId));
        }
        if (!tasks.existsById(taskId)) {
            tasks.save(newTask(taskId, apiSpecId, fixture));
        }
        if (!testCases.existsById(caseId)) {
            testCases.save(newTestCase(caseId, apiSpecId));
        }
        if (!executionRecords.existsById(executionId)) {
            executionRecords.save(newExecutionRecord(executionId, taskId, caseId, apiSpecId, fixture));
        }
        return taskId;
    }

    private Task newTask(String taskId, String apiSpecId, GoldenTaskFixture fixture) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Phase 8 report usefulness evaluation " + fixture.fixtureId());
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("phase8-agent-evaluation");
        task.setTargetApiSpecIds(List.of(apiSpecId));
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.PENDING);
        task.setPriority(TaskPriority.HIGH);
        task.setCreator("phase8-agent-evaluation");
        task.setMetadata(Map.of("fixtureId", fixture.fixtureId(), "capability", METRIC_NAME));
        return task;
    }

    private ApiSpec newApiSpec(String apiSpecId) {
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId(apiSpecId);
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("payment");
        apiSpec.setHttpMethod(HttpMethod.POST);
        apiSpec.setPath("/api/orders/pay");
        apiSpec.setSummary("Pay order");
        apiSpec.setDescription("Pay an order using the configured tenant credentials.");
        apiSpec.setOperationId("payOrder");
        apiSpec.setParameters(Map.of(
            "body", Map.of(
                "orderId", Map.of("type", "string", "required", true),
                "paymentMethod", Map.of("type", "string", "required", true)
            )
        ));
        apiSpec.setConstraints(Map.of("paymentMethod", Map.of("allowed", List.of("CARD", "BALANCE"))));
        apiSpec.setAuth(Map.of("type", "bearer", "roles", List.of("ORDER_MANAGER")));
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("openapi://orders.yaml#/paths/~1api~1orders~1pay/post");
        apiSpec.setSourceLocation(Map.of("line", 88));
        apiSpec.setVersion(1);
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(false);
        return apiSpec;
    }

    private TestCase newTestCase(String caseId, String apiSpecId) {
        var testCase = new TestCase();
        testCase.setCaseId(caseId);
        testCase.setPrimaryApiSpecId(apiSpecId);
        testCase.setCaseCategory(CaseCategory.API);
        testCase.setMode(TestCaseMode.SINGLE);
        testCase.setTitle("Generated auth failure report case");
        testCase.setDescription("Verifies report evidence for an authorization failure.");
        testCase.setPreconditions(List.of("A valid order exists"));
        testCase.setExpectedResult("The API returns a clear authorization failure.");
        testCase.setPriority(CasePriority.HIGH);
        testCase.setRiskLevel(CaseRiskLevel.HIGH);
        testCase.setTags(List.of("api", "auth", "report"));
        testCase.setScenarioName("Auth failure");
        testCase.setModuleName("payment");
        testCase.setStatus(CaseStatus.READY);
        testCase.setSource(CaseSource.STRUCTURE);
        testCase.setManualEdited(false);
        testCase.setLocked(false);
        testCase.setDetailType(DetailType.API);
        testCase.setDetail(Map.of("apiSpecId", apiSpecId));
        testCase.setSteps(List.of(Map.of(
            "order", 1,
            "apiSpecId", apiSpecId,
            "method", "POST",
            "path", "/api/orders/pay"
        )));
        testCase.setBasedOnApiSpecVersions(Map.of(apiSpecId, 1));
        testCase.setGeneratedFromSingleCaseIds(List.of());
        testCase.setUpdatedBy("phase8-agent-evaluation");
        return testCase;
    }

    private ExecutionRecord newExecutionRecord(
        String executionId,
        String taskId,
        String caseId,
        String apiSpecId,
        GoldenTaskFixture fixture
    ) {
        var setup = fixture.setupMetadata();
        var statusCode = integer(setup.get("statusCode"), 401);
        var record = new ExecutionRecord();
        record.setExecutionId(executionId);
        record.setTaskId(taskId);
        record.setCaseId(caseId);
        record.setStepId(UUID.nameUUIDFromBytes(("step:" + executionId).getBytes(StandardCharsets.UTF_8)).toString());
        record.setExecutorType(ExecutorType.HTTP);
        record.setEnvironment(text(setup, "environment", "qa"));
        record.setRequestSnapshot(Map.of(
            "method", "POST",
            "path", text(setup, "requestPath", "/api/orders/pay"),
            "headers", Map.of("Authorization", "[REDACTED]"),
            "apiSpecIds", List.of(apiSpecId)
        ));
        record.setResponseSnapshot(Map.of(
            "statusCode", statusCode,
            "failureType", "ASSERTION_FAILURE",
            "bodyType", "json"
        ));
        record.setAssertionResults(List.of(Map.of(
            "name", "expected status",
            "type", "STATUS_CODE",
            "expected", 200,
            "actual", statusCode,
            "status", "FAILED",
            "critical", true
        )));
        record.setOverallStatus(OverallStatus.valueOf(text(setup, "overallStatus", "FAILED")));
        record.setCriticalFailed(bool(setup.get("criticalFailed"), true));
        record.setDurationMs(longValue(setup.get("durationMs"), 73L));
        record.setStatusCode(statusCode);
        record.setErrorMessage(text(setup, "errorMessage", null));
        return record;
    }

    private ReportAsset assetFromReport(Report report) {
        return new ReportAsset(
            report.getSummary(),
            report.getCaseCount() == null ? 0 : report.getCaseCount(),
            report.getPassCount() == null ? 0 : report.getPassCount(),
            report.getFailCount() == null ? 0 : report.getFailCount(),
            report.getWarningCount() == null ? 0 : report.getWarningCount(),
            report.getRiskSummary(),
            report.getFindings() == null ? List.of() : report.getFindings(),
            report.getSuggestions() == null ? List.of() : report.getSuggestions(),
            report.getMetadata() == null ? Map.of() : report.getMetadata(),
            Map.of("reportId", report.getReportId(), "taskId", report.getTaskId())
        );
    }

    private ReportAsset assetFromMap(Map<String, Object> source) {
        return new ReportAsset(
            text(source, "summary", null),
            integer(source.get("caseCount"), 0),
            integer(source.get("passCount"), 0),
            integer(source.get("failCount"), 0),
            integer(source.get("warningCount"), 0),
            text(source, "riskSummary", null),
            listOfMaps(source.get("findings")),
            listOfMaps(source.get("suggestions")),
            map(source.get("metadata")),
            map(source.get("identifiers"))
        );
    }

    private Map<String, Object> actual(ReportAsset asset) {
        var actual = new LinkedHashMap<String, Object>();
        actual.put("summaryPresent", !blank(asset.summary()));
        actual.put("executionStats", executionStats(asset));
        actual.put("findingTypes", asset.findings().stream().map(finding -> text(finding, "type", null)).distinct().toList());
        actual.put("evidenceTypes", evidenceTypes(asset));
        actual.put("recommendationCount", asset.suggestions().size());
        actual.put("sourceReferenceEvidence", hasSourceReferenceEvidence(asset));
        actual.put("memoryFeedbackSummaryPresent", hasMemoryFeedbackSummary(asset));
        actual.put("secretLeakageDetected", hasSecretLeakage(asset));
        return actual;
    }

    private List<String> diagnostics(Map<String, Object> expected, ReportAsset asset) {
        var diagnostics = new ArrayList<String>();
        for (var section : list(expected.get("expectedReportSections"))) {
            if (!sectionPresent(section, asset)) {
                diagnostics.add("missing section: " + section);
            }
        }
        for (var evidenceType : list(expected.get("expectedEvidenceTypes"))) {
            if (!evidencePresent(evidenceType, asset)) {
                diagnostics.add("missing evidence: " + evidenceType);
            }
        }
        var expectedRecommendation = text(expected, "expectedRecommendationContains", null);
        if (expectedRecommendation != null && asset.suggestions().stream()
            .noneMatch(suggestion -> contains(suggestion, expectedRecommendation))) {
            diagnostics.add("missing recommendation: expected action related to " + expectedRecommendation);
        }
        if (bool(expected.get("forbidSecretLeakage"), false) && hasSecretLeakage(asset)) {
            diagnostics.add("secret leakage: report contains an unredacted sensitive field or credential-like value");
        }
        return diagnostics;
    }

    private boolean sectionPresent(String section, ReportAsset asset) {
        return switch (normalizeToken(section)) {
            case "summary" -> !blank(asset.summary()) && !blank(asset.riskSummary());
            case "execution-stats" -> {
                var stats = executionStats(asset);
                yield ((Number) stats.get("caseCount")).intValue() > 0
                    && stats.containsKey("passed")
                    && stats.containsKey("failed")
                    && stats.containsKey("skipped");
            }
            case "failure-evidence" -> asset.findings().stream()
                .anyMatch(finding -> "EXECUTION_OUTCOME".equals(text(finding, "type", null))
                    && (hasNonEmptyList(finding.get("factualEvidence"))
                    || hasNonEmptyList(finding.get("inferredEvidence"))
                    || hasSourceReferences(finding.get("sourceReferences"))));
            case "recommendations" -> !asset.suggestions().isEmpty();
            case "source-references", "citations" -> hasSourceReferenceEvidence(asset);
            case "memory-learning-summary", "memory-feedback" -> hasMemoryFeedbackSummary(asset);
            default -> false;
        };
    }

    private boolean evidencePresent(String evidenceType, ReportAsset asset) {
        return switch (normalizeToken(evidenceType)) {
            case "execution" -> ((Number) executionStats(asset).get("executionCount")).intValue() > 0
                || sourceReferenceValues(asset, "executionIds").stream().findAny().isPresent();
            case "observation" -> !sourceReferenceValues(asset, "observationIds").isEmpty()
                || asset.findings().stream().anyMatch(finding -> hasNonEmptyList(finding.get("inferredEvidence")))
                || hasNonEmptyList(map(asset.metadata().get("analysisCoverage")).get("generatedObservationIds"));
            case "citation" -> !sourceReferenceValues(asset, "apiSpecIds").isEmpty()
                || hasNonEmptyList(map(asset.metadata().get("coverage")).get("testedApiSpecIds"))
                || asset.findings().stream().anyMatch(finding -> hasNonEmptyList(finding.get("citations")));
            case "memory-feedback", "memory" -> hasMemoryFeedbackSummary(asset);
            default -> false;
        };
    }

    private Map<String, Object> executionStats(ReportAsset asset) {
        var summary = map(asset.metadata().get("executionSummary"));
        var stats = new LinkedHashMap<String, Object>();
        stats.put("caseCount", asset.caseCount());
        stats.put("executionCount", integer(summary.get("executionCount"), asset.passCount() + asset.failCount() + asset.warningCount()));
        stats.put("passed", integer(summary.get("passed"), asset.passCount()));
        stats.put("failed", integer(summary.get("failed"), asset.failCount()));
        stats.put("warning", integer(summary.get("warning"), asset.warningCount()));
        stats.put("skipped", integer(summary.get("skipped"), 0));
        stats.put("blocked", integer(summary.get("blocked"), 0));
        return stats;
    }

    private List<String> evidenceTypes(ReportAsset asset) {
        var evidence = new ArrayList<String>();
        for (var type : List.of("execution", "observation", "citation", "memory-feedback")) {
            if (evidencePresent(type, asset)) {
                evidence.add(type);
            }
        }
        return evidence;
    }

    private boolean hasSourceReferenceEvidence(ReportAsset asset) {
        return asset.findings().stream().anyMatch(finding -> hasSourceReferences(finding.get("sourceReferences")))
            || asset.suggestions().stream().anyMatch(suggestion -> hasSourceReferences(suggestion.get("sourceReferences")))
            || hasNonEmptyList(map(asset.metadata().get("coverage")).get("targetApiSpecIds"))
            || hasNonEmptyList(map(asset.metadata().get("coverage")).get("testedApiSpecIds"));
    }

    private boolean hasMemoryFeedbackSummary(ReportAsset asset) {
        var memoryFeedback = map(asset.metadata().get("memoryFeedback"));
        if (!blank(text(memoryFeedback, "summary", null))) {
            return true;
        }
        return integer(memoryFeedback.get("generatedCandidateAttemptCount"), 0) > 0
            || integer(memoryFeedback.get("acceptedLongTermMemoryCount"), 0) > 0
            || hasNonEmptyList(memoryFeedback.get("generatedCandidateResults"))
            || hasNonEmptyList(memoryFeedback.get("longTermMemoryReferences"));
    }

    private List<String> sourceReferenceValues(ReportAsset asset, String key) {
        var values = new ArrayList<String>();
        for (var finding : asset.findings()) {
            values.addAll(list(map(finding.get("sourceReferences")).get(key)));
        }
        for (var suggestion : asset.suggestions()) {
            values.addAll(list(map(suggestion.get("sourceReferences")).get(key)));
        }
        return values.stream().distinct().toList();
    }

    private boolean hasSourceReferences(Object value) {
        var references = map(value);
        return hasNonEmptyList(references.get("executionIds"))
            || hasNonEmptyList(references.get("observationIds"))
            || hasNonEmptyList(references.get("caseIds"))
            || hasNonEmptyList(references.get("apiSpecIds"));
    }

    private boolean hasSecretLeakage(ReportAsset asset) {
        return hasSecretLeakage(asset.toLeakCheckMap(), "");
    }

    private boolean hasSecretLeakage(Object value, String path) {
        if (value instanceof Map<?, ?> mapValue) {
            for (var entry : mapValue.entrySet()) {
                var key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                var childPath = path.isBlank() ? key : path + "." + key;
                var sensitiveKey = sensitiveKey(key);
                if (sensitiveKey && !redacted(entry.getValue())) {
                    return true;
                }
                if (hasSecretLeakage(entry.getValue(), childPath)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Iterable<?> iterable) {
            for (var item : iterable) {
                if (hasSecretLeakage(item, path)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof String text) {
            return containsCredentialLikeValue(text);
        }
        return false;
    }

    private boolean sensitiveKey(String key) {
        var normalized = normalizeToken(key);
        var lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.equals("authorization")
            || normalized.equals("cookie")
            || normalized.equals("password")
            || normalized.equals("secret")
            || normalized.equals("token")
            || lower.endsWith("password")
            || lower.endsWith("secret")
            || lower.endsWith("token");
    }

    private boolean redacted(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Map<?, ?> mapValue) {
            return mapValue.values().stream().allMatch(this::redacted);
        }
        if (value instanceof Iterable<?> iterable) {
            for (var item : iterable) {
                if (!redacted(item)) {
                    return false;
                }
            }
            return true;
        }
        var text = String.valueOf(value).trim();
        return text.isBlank()
            || "[REDACTED]".equalsIgnoreCase(text)
            || "REDACTED".equalsIgnoreCase(text)
            || "***".equals(text)
            || "****".equals(text);
    }

    private boolean containsCredentialLikeValue(String value) {
        var lower = value.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\b(bearer|basic)\\s+(?!\\[redacted\\]|redacted|\\*\\*\\*)\\S{8,}.*")
            || lower.matches(
                ".*\\b(authorization|cookie|password|secret|token)\\b\\s*[:=]\\s*"
                    + "(?!\\[redacted\\]|redacted|\\*\\*\\*)\\S{8,}.*"
            );
    }

    private double score(Map<String, Object> expected, List<String> diagnostics) {
        var checks = 0;
        checks += list(expected.get("expectedReportSections")).size();
        checks += list(expected.get("expectedEvidenceTypes")).size();
        checks += expected.containsKey("expectedRecommendationContains") ? 1 : 0;
        checks += expected.containsKey("forbidSecretLeakage") ? 1 : 0;
        if (checks == 0) {
            return diagnostics.isEmpty() ? 1.0d : 0.0d;
        }
        return Math.max(0.0d, Math.round(((checks - diagnostics.size()) / (double) checks) * 10000.0d) / 10000.0d);
    }

    private boolean contains(Map<String, Object> map, String expected) {
        return containsValue(map, expected.toLowerCase(Locale.ROOT));
    }

    private boolean containsValue(Object value, String expected) {
        if (value instanceof Map<?, ?> mapValue) {
            return mapValue.values().stream().anyMatch(item -> containsValue(item, expected));
        }
        if (value instanceof Iterable<?> iterable) {
            for (var item : iterable) {
                if (containsValue(item, expected)) {
                    return true;
                }
            }
            return false;
        }
        return value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(expected);
    }

    private boolean hasNonEmptyList(Object value) {
        return value instanceof Iterable<?> iterable && iterable.iterator().hasNext();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String id(EvaluationRunContext context, GoldenTaskFixture fixture, String suffix) {
        var raw = context.fixtureNamespace() + ":" + fixture.fixtureId() + ":" + suffix;
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
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

    private String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
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

    private List<String> list(Object value) {
        if (value instanceof Iterable<?> iterable) {
            var values = new ArrayList<String>();
            for (var item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item).trim());
                }
            }
            return values;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return List.of();
        }
        return List.of(String.valueOf(value).trim());
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        var values = new ArrayList<Map<String, Object>>();
        for (var item : iterable) {
            if (item instanceof Map<?, ?> map) {
                values.add(map(map));
            }
        }
        return values;
    }

    private record ReportAsset(
        String summary,
        int caseCount,
        int passCount,
        int failCount,
        int warningCount,
        String riskSummary,
        List<Map<String, Object>> findings,
        List<Map<String, Object>> suggestions,
        Map<String, Object> metadata,
        Map<String, Object> identifiers
    ) {

        private Map<String, Object> toLeakCheckMap() {
            var values = new LinkedHashMap<String, Object>();
            values.put("summary", summary);
            values.put("riskSummary", riskSummary);
            values.put("findings", findings);
            values.put("suggestions", suggestions);
            values.put("metadata", metadata);
            values.put("identifiers", identifiers);
            return values;
        }
    }
}
