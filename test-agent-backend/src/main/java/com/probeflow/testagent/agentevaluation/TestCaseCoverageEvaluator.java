package com.probeflow.testagent.agentevaluation;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.knowledge.DocumentAuthority;
import com.probeflow.testagent.knowledge.DocumentSourceType;
import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.knowledge.KnowledgeContentFormat;
import com.probeflow.testagent.knowledge.KnowledgeIngestApplicationService;
import com.probeflow.testagent.knowledge.KnowledgeIngestRequest;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import com.probeflow.testagent.testcasedraft.TestCaseDraft;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
import com.probeflow.testagent.testcasegeneration.ScenarioCategory;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationApplicationService;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationMode;
import com.probeflow.testagent.testcasegeneration.TestCaseGenerationRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TestCaseCoverageEvaluator implements AgentEvaluationEvaluator {

    public static final String METRIC_NAME = "case-coverage";

    private final TestCaseGenerationApplicationService generationService;
    private final TestCaseDraftRepository drafts;
    private final TaskRepository tasks;
    private final ApiSpecRepository apiSpecs;
    private final KnowledgeIngestApplicationService knowledgeIngest;

    public TestCaseCoverageEvaluator(
        TestCaseGenerationApplicationService generationService,
        TestCaseDraftRepository drafts,
        TaskRepository tasks,
        ApiSpecRepository apiSpecs,
        KnowledgeIngestApplicationService knowledgeIngest
    ) {
        this.generationService = generationService;
        this.drafts = drafts;
        this.tasks = tasks;
        this.apiSpecs = apiSpecs;
        this.knowledgeIngest = knowledgeIngest;
    }

    @Override
    public String capability() {
        return METRIC_NAME;
    }

    @Override
    public boolean supports(GoldenTaskFixture fixture) {
        return fixture.fixtureType() == EvaluationFixtureType.TEST_CASE_COVERAGE;
    }

    @Override
    public EvaluationCaseResult evaluate(
        EvaluationDataset dataset,
        GoldenTaskFixture fixture,
        EvaluationRunContext context
    ) {
        var assets = assets(fixture, context);
        var expected = fixture.expectedResults();
        var actual = actual(assets);
        var diagnostics = diagnostics(expected, assets);
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
                ? "Generated test assets covered expected scenarios without duplicate penalty."
                : String.join("; ", diagnostics)
        );

        if (passed) {
            return EvaluationCaseResult.passed(
                fixture.fixtureId(),
                fixture.capabilityTags(),
                "Generated " + assets.size() + " test assets with coverage " + coverageCategories(assets) + ".",
                expected.toString(),
                List.of(metric)
            );
        }
        return EvaluationCaseResult.failed(
            fixture.fixtureId(),
            fixture.capabilityTags(),
            "Generated test assets missed expected coverage or duplicate checks.",
            expected.toString(),
            diagnostics,
            List.of(metric)
        );
    }

    private List<CoverageAsset> assets(GoldenTaskFixture fixture, EvaluationRunContext context) {
        var setupAssets = listOfMaps(fixture.setupMetadata().get("testAssets"));
        if (!setupAssets.isEmpty()) {
            return setupAssets.stream().map(this::assetFromMap).toList();
        }
        var seed = seedGenerationFixture(fixture, context);
        generationService.generate(new TestCaseGenerationRequest(
            seed.taskId(),
            context.runId(),
            seed.apiSpecIds(),
            seed.generationMode(),
            seed.scenarioCategories(),
            integer(fixture.setupMetadata().get("tokenBudget"), 700)
        ));
        return drafts.findAllByTaskIdOrderByCreatedAtAscDraftIdAsc(seed.taskId()).stream()
            .map(this::assetFromDraft)
            .toList();
    }

    private GenerationSeed seedGenerationFixture(GoldenTaskFixture fixture, EvaluationRunContext context) {
        var setup = fixture.setupMetadata();
        var mode = TestCaseGenerationMode.valueOf(text(setup, "generationMode", "SINGLE"));
        var taskId = id(context, fixture, "task");
        var apiSpecIds = new ArrayList<String>();
        var createOrderApiSpecId = id(context, fixture, "api-create-order");
        seedApiSpec(createOrderApiSpecId, newOrderApiSpec(createOrderApiSpecId));
        apiSpecIds.add(createOrderApiSpecId);

        if (mode == TestCaseGenerationMode.SUITE) {
            var readOrderApiSpecId = id(context, fixture, "api-read-order");
            seedApiSpec(readOrderApiSpecId, newReadOrderApiSpec(readOrderApiSpecId));
            apiSpecIds.add(readOrderApiSpecId);
        }

        if (!tasks.existsById(taskId)) {
            tasks.save(newTask(taskId, apiSpecIds, fixture));
        }
        if (bool(setup.get("includeKnowledge"), false)) {
            ingestBusinessRule(fixture, context);
        }
        return new GenerationSeed(taskId, List.copyOf(apiSpecIds), mode, scenarioCategories(setup, mode));
    }

    private void seedApiSpec(String apiSpecId, ApiSpec apiSpec) {
        if (!apiSpecs.existsById(apiSpecId)) {
            apiSpecs.save(apiSpec);
        }
    }

    private Task newTask(String taskId, List<String> apiSpecIds, GoldenTaskFixture fixture) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_ANALYSIS);
        task.setTaskName("Phase 8 case coverage evaluation " + fixture.fixtureId());
        task.setStatus(TaskStatus.COMPLETED);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("phase8-agent-evaluation");
        task.setTargetApiSpecIds(apiSpecIds);
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.NOT_REQUIRED);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase8-agent-evaluation");
        task.setMetadata(Map.of("fixtureId", fixture.fixtureId(), "capability", METRIC_NAME));
        return task;
    }

    private ApiSpec newOrderApiSpec(String apiSpecId) {
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId(apiSpecId);
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("order");
        apiSpec.setHttpMethod(HttpMethod.POST);
        apiSpec.setPath("/api/orders");
        apiSpec.setSummary("Create order");
        apiSpec.setDescription("Create a new order from a SKU and quantity");
        apiSpec.setOperationId("createOrder");
        apiSpec.setParameters(Map.of(
            "body", Map.of(
                "skuId", Map.of("type", "string", "required", true),
                "quantity", Map.of("type", "integer", "required", true)
            )
        ));
        apiSpec.setConstraints(Map.of("quantity", Map.of("min", 1, "max", 99)));
        apiSpec.setAuth(Map.of("type", "bearer", "header", "Authorization", "roles", List.of("ORDER_MANAGER")));
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("openapi://orders.yaml#/paths/~1api~1orders/post");
        apiSpec.setSourceLocation(Map.of("line", 42));
        apiSpec.setVersion(3);
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(false);
        return apiSpec;
    }

    private ApiSpec newReadOrderApiSpec(String apiSpecId) {
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId(apiSpecId);
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("order");
        apiSpec.setHttpMethod(HttpMethod.GET);
        apiSpec.setPath("/api/orders/{orderId}");
        apiSpec.setSummary("Read order");
        apiSpec.setDescription("Read an order by id");
        apiSpec.setOperationId("readOrder");
        apiSpec.setParameters(Map.of(
            "path", Map.of("orderId", Map.of("type", "string", "required", true))
        ));
        apiSpec.setConstraints(Map.of());
        apiSpec.setAuth(Map.of("type", "bearer", "roles", List.of("ORDER_MANAGER")));
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("openapi://orders.yaml#/paths/~1api~1orders~1{orderId}/get");
        apiSpec.setSourceLocation(Map.of("line", 53));
        apiSpec.setVersion(2);
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(false);
        return apiSpec;
    }

    private void ingestBusinessRule(GoldenTaskFixture fixture, EvaluationRunContext context) {
        knowledgeIngest.ingest(new KnowledgeIngestRequest(
            "Order quantity business rule",
            KnowledgeContentFormat.MARKDOWN,
            "# Order quantity business rule\n\nPOST /api/orders requires quantity between 1 and 99.",
            DocumentSourceType.WIKI,
            "phase8/case-coverage/" + fixture.fixtureId() + "/" + context.runId() + ".md",
            DocumentType.DOMAIN_RULE,
            DocumentAuthority.HIGH,
            "order-platform",
            "order",
            "order",
            List.of("order", "quantity"),
            List.of("case_generation"),
            Map.of("fixtureId", fixture.fixtureId())
        ));
    }

    private Map<String, Object> actual(List<CoverageAsset> assets) {
        var actual = new LinkedHashMap<String, Object>();
        actual.put("assetCount", assets.size());
        actual.put("coverageCategories", coverageCategories(assets));
        actual.put("scenarioCategories", assets.stream().map(CoverageAsset::scenarioCategory).distinct().toList());
        actual.put("scenarioNames", assets.stream().map(CoverageAsset::scenarioName).filter(value -> value != null).distinct().toList());
        actual.put("tags", assets.stream().flatMap(asset -> asset.tags().stream()).distinct().toList());
        actual.put("requestVariationEvidence", assets.stream().filter(CoverageAsset::hasRequestEvidence).map(CoverageAsset::assetId).toList());
        actual.put("assertionEvidence", assets.stream().filter(CoverageAsset::hasAssertionEvidence).map(CoverageAsset::assetId).toList());
        actual.put("suiteDependencyEvidence", assets.stream().filter(CoverageAsset::hasSuiteDependencyEvidence).map(CoverageAsset::assetId).toList());
        actual.put("duplicateCount", duplicateCount(assets));
        return actual;
    }

    private List<String> diagnostics(Map<String, Object> expected, List<CoverageAsset> assets) {
        var diagnostics = new ArrayList<String>();
        var coverage = coverageCategories(assets);
        for (var category : list(expected.get("expectedCoverageCategories"))) {
            if (!coverage.contains(normalizeCoverageCategory(category))) {
                diagnostics.add("missing coverage category: " + category);
            }
        }
        missingRequiredScenario(expected, assets, "requiredHappyPathScenario", "missing happy path", diagnostics);
        missingRequiredScenarios(expected, assets, "requiredValidationNegativeCases", "missing validation negative case", diagnostics);
        missingRequiredScenarios(expected, assets, "requiredAuthNegativeCases", "missing auth negative case", diagnostics);
        missingRequiredScenarios(expected, assets, "requiredBoundaryValueCases", "missing boundary value case", diagnostics);
        missingRequiredScenarios(expected, assets, "requiredBusinessRuleCases", "missing business rule case", diagnostics);
        if (bool(expected.get("requiredSuiteDependencyCoverage"), false)
            && assets.stream().noneMatch(CoverageAsset::hasSuiteDependencyEvidence)) {
            diagnostics.add("missing suite dependency coverage");
        }
        if (bool(expected.get("requireRequestVariationEvidence"), false)
            && assets.stream().anyMatch(asset -> !asset.hasRequestEvidence())) {
            diagnostics.add("missing request variation evidence");
        }
        if (bool(expected.get("requireAssertionEvidence"), false)
            && assets.stream().anyMatch(asset -> !asset.hasAssertionEvidence())) {
            diagnostics.add("missing assertion evidence");
        }
        if (bool(expected.get("requireScenarioMetadata"), false)
            && assets.stream().anyMatch(asset -> !asset.hasScenarioMetadata())) {
            diagnostics.add("missing scenario metadata");
        }
        if (expected.containsKey("allowedDuplicateCount")) {
            var allowed = integer(expected.get("allowedDuplicateCount"), 0);
            var duplicates = duplicateCount(assets);
            if (duplicates > allowed) {
                diagnostics.add("duplicate penalty: duplicate count " + duplicates + " exceeds allowed " + allowed);
            }
        }
        return diagnostics;
    }

    private void missingRequiredScenario(
        Map<String, Object> expected,
        List<CoverageAsset> assets,
        String key,
        String diagnostic,
        List<String> diagnostics
    ) {
        var required = text(expected, key, null);
        if (required != null && assets.stream().noneMatch(asset -> asset.matches(required))) {
            diagnostics.add(diagnostic + ": " + required);
        }
    }

    private void missingRequiredScenarios(
        Map<String, Object> expected,
        List<CoverageAsset> assets,
        String key,
        String diagnostic,
        List<String> diagnostics
    ) {
        for (var required : list(expected.get(key))) {
            if (assets.stream().noneMatch(asset -> asset.matches(required))) {
                diagnostics.add(diagnostic + ": " + required);
            }
        }
    }

    private double score(Map<String, Object> expected, List<String> diagnostics) {
        var checks = 0;
        checks += list(expected.get("expectedCoverageCategories")).size();
        checks += expected.containsKey("requiredHappyPathScenario") ? 1 : 0;
        checks += list(expected.get("requiredValidationNegativeCases")).size();
        checks += list(expected.get("requiredAuthNegativeCases")).size();
        checks += list(expected.get("requiredBoundaryValueCases")).size();
        checks += list(expected.get("requiredBusinessRuleCases")).size();
        checks += expected.containsKey("requiredSuiteDependencyCoverage") ? 1 : 0;
        checks += expected.containsKey("requireRequestVariationEvidence") ? 1 : 0;
        checks += expected.containsKey("requireAssertionEvidence") ? 1 : 0;
        checks += expected.containsKey("requireScenarioMetadata") ? 1 : 0;
        checks += expected.containsKey("allowedDuplicateCount") ? 1 : 0;
        if (checks == 0) {
            return diagnostics.isEmpty() ? 1.0d : 0.0d;
        }
        return Math.max(0.0d, Math.round(((checks - diagnostics.size()) / (double) checks) * 10000.0d) / 10000.0d);
    }

    private CoverageAsset assetFromDraft(TestCaseDraft draft) {
        var content = draft.getDraftContent() == null ? Map.<String, Object>of() : draft.getDraftContent();
        return new CoverageAsset(
            draft.getDraftId(),
            text(content, "scenarioCategory", null),
            text(content, "scenarioName", null),
            list(content.get("tags")),
            draft.getExpectedStatusCode() == null
                ? integer(content.get("expectedStatus"), null)
                : draft.getExpectedStatusCode(),
            map(content.get("requestShape")),
            listOfMaps(content.get("steps")),
            listOfMaps(content.get("assertions")),
            text(content, "expectedResult", null),
            draft.getDedupKey()
        );
    }

    private CoverageAsset assetFromMap(Map<String, Object> source) {
        return new CoverageAsset(
            text(source, "assetId", "asset-" + UUID.randomUUID()),
            text(source, "scenarioCategory", null),
            text(source, "scenarioName", null),
            list(source.get("tags")),
            integer(source.get("expectedStatus"), null),
            map(source.get("requestShape")),
            listOfMaps(source.get("steps")),
            listOfMaps(source.get("assertions")),
            text(source, "expectedResult", null),
            text(source, "dedupKey", null)
        );
    }

    private List<String> coverageCategories(List<CoverageAsset> assets) {
        var categories = new LinkedHashSet<String>();
        for (var asset : assets) {
            switch (normalizeToken(asset.scenarioCategory())) {
                case "happy-path" -> categories.add("happy-path");
                case "missing-required", "invalid-value" -> categories.add("validation-negative");
                case "authentication-failure", "permission-failure" -> categories.add("auth-negative");
                case "boundary-value" -> categories.add("boundary-value");
                case "business-rule" -> categories.add("business-rule");
                case "business-flow" -> categories.add("suite-dependency");
                default -> {
                }
            }
            for (var tag : asset.tags()) {
                var normalizedTag = normalizeCoverageCategory(tag);
                if (!normalizedTag.isBlank()) {
                    categories.add(normalizedTag);
                }
            }
        }
        return List.copyOf(categories);
    }

    private int duplicateCount(List<CoverageAsset> assets) {
        var signatures = new LinkedHashSet<String>();
        for (var asset : assets) {
            signatures.add(asset.signature());
        }
        return Math.max(0, assets.size() - signatures.size());
    }

    private List<ScenarioCategory> scenarioCategories(Map<String, Object> setup, TestCaseGenerationMode mode) {
        var configured = list(setup.get("scenarioCategories"));
        if (configured.isEmpty() && mode == TestCaseGenerationMode.SUITE) {
            return List.of(ScenarioCategory.BUSINESS_FLOW);
        }
        if (configured.isEmpty()) {
            return List.of(ScenarioCategory.HAPPY_PATH);
        }
        return configured.stream().map(ScenarioCategory::valueOf).toList();
    }

    private String normalizeCoverageCategory(String value) {
        var normalized = normalizeToken(value);
        return switch (normalized) {
            case "happy-path" -> "happy-path";
            case "missing-required", "invalid-value", "validation-negative" -> "validation-negative";
            case "authentication-failure", "permission-failure", "auth-negative" -> "auth-negative";
            case "boundary-value" -> "boundary-value";
            case "business-rule" -> "business-rule";
            case "business-flow", "suite-dependency" -> "suite-dependency";
            default -> "";
        };
    }

    private String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
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

    private record GenerationSeed(
        String taskId,
        List<String> apiSpecIds,
        TestCaseGenerationMode generationMode,
        List<ScenarioCategory> scenarioCategories
    ) {
    }

    private record CoverageAsset(
        String assetId,
        String scenarioCategory,
        String scenarioName,
        List<String> tags,
        Integer expectedStatus,
        Map<String, Object> requestShape,
        List<Map<String, Object>> steps,
        List<Map<String, Object>> assertions,
        String expectedResult,
        String dedupKey
    ) {

        boolean matches(String expected) {
            var normalized = normalize(expected);
            return normalize(scenarioCategory).equals(normalized)
                || normalize(scenarioName).equals(normalized)
                || tags.stream().map(CoverageAsset::normalize).anyMatch(normalized::equals);
        }

        boolean hasRequestEvidence() {
            return !requestShape.isEmpty() || steps.stream().anyMatch(step -> !step.isEmpty());
        }

        boolean hasAssertionEvidence() {
            return expectedStatus != null
                || (expectedResult != null && !expectedResult.isBlank())
                || !assertions.isEmpty()
                || steps.stream().anyMatch(step -> step.containsKey("expectedStatus"));
        }

        boolean hasScenarioMetadata() {
            return (scenarioCategory != null && !scenarioCategory.isBlank())
                && ((scenarioName != null && !scenarioName.isBlank()) || !tags.isEmpty());
        }

        boolean hasSuiteDependencyEvidence() {
            return "business-flow".equals(normalize(scenarioCategory))
                && steps.size() >= 2
                && steps.stream().allMatch(step -> step.containsKey("order")
                    && step.containsKey("apiSpecId")
                    && step.containsKey("expectedStatus"));
        }

        String signature() {
            if (dedupKey != null && !dedupKey.isBlank()) {
                return dedupKey;
            }
            return normalize(scenarioCategory)
                + "|" + normalize(scenarioName)
                + "|" + expectedStatus
                + "|" + requestShape
                + "|" + steps;
        }

        private static String normalize(String value) {
            if (value == null) {
                return "";
            }
            return value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }
}
