package com.probeflow.testagent.testcasegeneration;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.memory.ContextBundle;
import com.probeflow.testagent.memory.UnifiedContextBuilder;
import com.probeflow.testagent.memory.UnifiedContextQuery;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import com.probeflow.testagent.testcasedraft.TestCaseDraft;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TestCaseGenerationApplicationService {

    private static final int DEFAULT_TOKEN_BUDGET = 1200;
    private static final String STAGE_PROFILE = "case_generation";

    private final TaskRepository tasks;
    private final ApiSpecRepository apiSpecs;
    private final TestCaseDraftRepository drafts;
    private final UnifiedContextBuilder unifiedContextBuilder;

    public TestCaseGenerationApplicationService(
        TaskRepository tasks,
        ApiSpecRepository apiSpecs,
        TestCaseDraftRepository drafts,
        UnifiedContextBuilder unifiedContextBuilder
    ) {
        this.tasks = tasks;
        this.apiSpecs = apiSpecs;
        this.drafts = drafts;
        this.unifiedContextBuilder = unifiedContextBuilder;
    }

    @Transactional
    public TestCaseGenerationResult generate(TestCaseGenerationRequest request) {
        validateSingleRequest(request);

        var task = tasks.findById(request.taskId())
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + request.taskId()));
        var apiSpecId = request.targetApiSpecIds().getFirst();
        var apiSpec = apiSpecs.findById(apiSpecId)
            .orElseThrow(() -> new IllegalArgumentException("ApiSpec not found: " + apiSpecId));
        assertStructurallyValid(apiSpec);

        var requestedCategories = requestedCategories(request);
        var context = unifiedContextBuilder.build(new UnifiedContextQuery(
            task.getTaskId(),
            request.sessionId(),
            apiSpec.getApiSpecId(),
            null,
            STAGE_PROFILE,
            rawQueryFor(apiSpec),
            apiSpec.getSystemName(),
            apiSpec.getModuleName(),
            apiSpec.getPath(),
            null,
            contextTags(apiSpec, requestedCategories),
            tokenBudget(request)
        ));

        var plan = planScenarioIntents(apiSpec, requestedCategories);
        var createdDraftIds = new ArrayList<String>();
        for (var intent : plan.intents()) {
            var draft = drafts.save(draftFromIntent(task.getTaskId(), task.getPromotionMode(), apiSpec, context, tokenBudget(request), intent));
            createdDraftIds.add(draft.getDraftId());
        }
        return new TestCaseGenerationResult(
            task.getTaskId(),
            request.sessionId(),
            request.generationMode(),
            List.of(apiSpec.getApiSpecId()),
            List.copyOf(createdDraftIds),
            plan.intents().stream().map(ScenarioIntent::category).toList(),
            plan.skippedCategories(),
            plan.unsupportedCategories(),
            new TestCaseGenerationCounts(createdDraftIds.size(), 0, plan.skippedCategories().size() + plan.unsupportedCategories().size(), 0)
        );
    }

    private void validateSingleRequest(TestCaseGenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Generation request is required");
        }
        if (!StringUtils.hasText(request.taskId())) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (request.generationMode() != TestCaseGenerationMode.SINGLE) {
            throw new IllegalArgumentException("Issue 01 supports SINGLE generation only");
        }
        if (request.targetApiSpecIds() == null || request.targetApiSpecIds().size() != 1) {
            throw new IllegalArgumentException("SINGLE generation requires exactly one target ApiSpec");
        }
    }

    private void assertStructurallyValid(ApiSpec apiSpec) {
        if (apiSpec.getHttpMethod() == null || !StringUtils.hasText(apiSpec.getPath())) {
            throw new IllegalArgumentException("ApiSpec is missing route structure: " + apiSpec.getApiSpecId());
        }
        if (!apiSpec.isRouteReady() || !apiSpec.isBasicParamReady()) {
            throw new IllegalArgumentException("ApiSpec is not ready for case generation: " + apiSpec.getApiSpecId());
        }
    }

    private TestCaseDraft draftFromIntent(
        String taskId,
        PromotionMode promotionMode,
        ApiSpec apiSpec,
        ContextBundle context,
        int tokenBudget,
        ScenarioIntent intent
    ) {
        var draft = new TestCaseDraft();
        draft.setTaskId(taskId);
        draft.setSource(CaseSource.STRUCTURE);
        draft.setStage(CaseSource.STRUCTURE);
        draft.setStatus(DraftStatus.PENDING_REVIEW);
        draft.setPromotionMode(promotionMode);
        draft.setTargetApiSpecId(apiSpec.getApiSpecId());
        draft.setDedupKey(dedupKey(taskId, apiSpec.getApiSpecId(), intent));
        draft.setExpectedStatusCode(intent.expectedStatus());
        draft.setDraftContent(draftContent(apiSpec, context, tokenBudget, intent));
        return draft;
    }

    private Map<String, Object> draftContent(
        ApiSpec apiSpec,
        ContextBundle context,
        int tokenBudget,
        ScenarioIntent intent
    ) {
        var content = new LinkedHashMap<String, Object>();
        content.put("title", intent.title());
        content.put("description", intent.description());
        content.put("preconditions", List.of("A valid API client is available", "Required request data is prepared"));
        content.put("steps", List.of(Map.of(
            "order", 1,
            "action", "Call HTTP API",
            "apiSpecId", apiSpec.getApiSpecId(),
            "method", apiSpec.getHttpMethod().name(),
            "path", apiSpec.getPath(),
            "requestShape", intent.requestShape()
        )));
        content.put("expectedResult", expectedResult(intent));
        content.put("requestShape", intent.requestShape());
        content.put("expectedStatus", intent.expectedStatus());
        content.put("scenarioCategory", intent.category().name());
        content.put("scenarioName", scenarioName(intent.category()));
        content.put("moduleName", apiSpec.getModuleName());
        content.put("tags", intent.tags());
        content.put("validationHints", intent.validationHints());
        content.put("priorityHint", intent.priorityHint());
        content.put("riskHint", intent.riskHint());
        content.put("generationMetadata", Map.of(
            "mode", TestCaseGenerationMode.SINGLE.name(),
            "generator", "deterministic-baseline",
            "stageProfile", STAGE_PROFILE,
            "apiSpecVersion", apiSpec.getVersion(),
            "contextBuilt", context.apiContext() != null,
            "contextBudgetRequested", tokenBudget,
            "source", CaseSource.STRUCTURE.name(),
            "scenarioIntent", Map.of(
                "category", intent.category().name(),
                "intentKey", intent.intentKey(),
                "expectedStatus", intent.expectedStatus()
            )
        ));
        return content;
    }

    private ScenarioPlan planScenarioIntents(ApiSpec apiSpec, List<ScenarioCategory> requestedCategories) {
        var intents = new ArrayList<ScenarioIntent>();
        var skipped = new LinkedHashMap<ScenarioCategory, String>();
        var unsupported = new LinkedHashMap<ScenarioCategory, String>();
        var parameters = parameters(apiSpec);
        var requiredParameter = parameters.stream().filter(ParameterRef::required).findFirst();
        var constrainedParameter = firstConstrainedParameter(apiSpec, parameters);

        for (var category : requestedCategories) {
            switch (category) {
                case HAPPY_PATH -> intents.add(happyPathIntent(apiSpec));
                case MISSING_REQUIRED -> {
                    if (requiredParameter.isPresent()) {
                        intents.add(missingRequiredIntent(apiSpec, requiredParameter.get()));
                    } else {
                        skipped.put(category, "No required parameters are represented in ApiSpec metadata");
                    }
                }
                case INVALID_VALUE -> {
                    if (constrainedParameter != null) {
                        intents.add(invalidValueIntent(apiSpec, constrainedParameter));
                    } else if (!parameters.isEmpty()) {
                        intents.add(invalidValueIntent(apiSpec, parameters.getFirst()));
                    } else {
                        skipped.put(category, "No parameters are represented for invalid-value planning");
                    }
                }
                case BOUNDARY_VALUE -> {
                    if (constrainedParameter != null) {
                        intents.add(boundaryValueIntent(apiSpec, constrainedParameter));
                    } else {
                        skipped.put(category, "No parameter constraints are represented for boundary planning");
                    }
                }
                case AUTHENTICATION_FAILURE -> {
                    if (hasAuthMetadata(apiSpec)) {
                        intents.add(authenticationFailureIntent(apiSpec));
                    } else {
                        unsupported.put(category, "No authentication metadata is represented for this ApiSpec");
                    }
                }
                case PERMISSION_FAILURE -> {
                    if (hasPermissionHints(apiSpec)) {
                        intents.add(permissionFailureIntent(apiSpec));
                    } else {
                        unsupported.put(category, "No role, permission, or scope hints are represented for this ApiSpec");
                    }
                }
            }
        }

        return new ScenarioPlan(List.copyOf(intents), Map.copyOf(skipped), Map.copyOf(unsupported));
    }

    private ScenarioIntent happyPathIntent(ApiSpec apiSpec) {
        var expectedStatus = successStatus(apiSpec.getHttpMethod());
        var titleSubject = StringUtils.hasText(apiSpec.getSummary())
            ? apiSpec.getSummary()
            : apiSpec.getHttpMethod() + " " + apiSpec.getPath();
        return new ScenarioIntent(
            ScenarioCategory.HAPPY_PATH,
            "happy-path",
            titleSubject + " happy path",
            "Verify the normal successful HTTP API behavior for " + apiSpec.getPath() + ".",
            expectedStatus,
            requestShape(apiSpec),
            List.of("Expect a successful response body"),
            tags(apiSpec, ScenarioCategory.HAPPY_PATH),
            "P1",
            "MEDIUM"
        );
    }

    private ScenarioIntent missingRequiredIntent(ApiSpec apiSpec, ParameterRef parameter) {
        var shape = requestShape(apiSpec);
        shape.put("mutation", Map.of(
            "type", "omit-required-parameter",
            "section", parameter.section(),
            "parameter", parameter.name()
        ));
        return new ScenarioIntent(
            ScenarioCategory.MISSING_REQUIRED,
            "missing-required-" + parameter.name(),
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " missing required " + parameter.name(),
            "Verify the API rejects a request when required parameter " + parameter.name() + " is omitted.",
            400,
            shape,
            List.of("Required parameter should be validated before business processing", "Missing parameter: " + parameter.name()),
            tags(apiSpec, ScenarioCategory.MISSING_REQUIRED),
            "P1",
            "HIGH"
        );
    }

    private ScenarioIntent invalidValueIntent(ApiSpec apiSpec, ParameterRef parameter) {
        var shape = requestShape(apiSpec);
        shape.put("mutation", Map.of(
            "type", "invalid-value",
            "section", parameter.section(),
            "parameter", parameter.name(),
            "invalidValue", invalidValueFor(parameter)
        ));
        return new ScenarioIntent(
            ScenarioCategory.INVALID_VALUE,
            "invalid-value-" + parameter.name(),
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " invalid " + parameter.name(),
            "Verify the API rejects an invalid value for parameter " + parameter.name() + ".",
            400,
            shape,
            List.of("Invalid value should produce a contract validation error", "Parameter under test: " + parameter.name()),
            tags(apiSpec, ScenarioCategory.INVALID_VALUE),
            "P1",
            "HIGH"
        );
    }

    private ScenarioIntent boundaryValueIntent(ApiSpec apiSpec, ParameterRef parameter) {
        var shape = requestShape(apiSpec);
        shape.put("mutation", Map.of(
            "type", "boundary-value",
            "section", parameter.section(),
            "parameter", parameter.name(),
            "constraint", constraintSnapshot(parameter)
        ));
        return new ScenarioIntent(
            ScenarioCategory.BOUNDARY_VALUE,
            "boundary-value-" + parameter.name(),
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " boundary " + parameter.name(),
            "Verify parameter " + parameter.name() + " behaves correctly around documented boundaries.",
            400,
            shape,
            List.of("Exercise min/max or length boundary from ApiSpec constraints", "Parameter under test: " + parameter.name()),
            tags(apiSpec, ScenarioCategory.BOUNDARY_VALUE),
            "P2",
            "MEDIUM"
        );
    }

    private ScenarioIntent authenticationFailureIntent(ApiSpec apiSpec) {
        var shape = requestShape(apiSpec);
        shape.put("mutation", Map.of("type", "omit-authentication"));
        return new ScenarioIntent(
            ScenarioCategory.AUTHENTICATION_FAILURE,
            "authentication-failure",
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " rejects missing authentication",
            "Verify the API rejects calls without required authentication credentials.",
            401,
            shape,
            List.of("Authentication metadata should cause unauthenticated requests to be rejected"),
            tags(apiSpec, ScenarioCategory.AUTHENTICATION_FAILURE),
            "P1",
            "HIGH"
        );
    }

    private ScenarioIntent permissionFailureIntent(ApiSpec apiSpec) {
        var shape = requestShape(apiSpec);
        shape.put("mutation", Map.of("type", "insufficient-permission"));
        return new ScenarioIntent(
            ScenarioCategory.PERMISSION_FAILURE,
            "permission-failure",
            apiSpec.getHttpMethod() + " " + apiSpec.getPath() + " rejects insufficient permissions",
            "Verify the API rejects authenticated callers without the represented role, permission, or scope.",
            403,
            shape,
            List.of("Permission metadata should cause under-privileged requests to be rejected"),
            tags(apiSpec, ScenarioCategory.PERMISSION_FAILURE),
            "P1",
            "HIGH"
        );
    }

    private Map<String, Object> requestShape(ApiSpec apiSpec) {
        var shape = new LinkedHashMap<String, Object>();
        shape.put("method", apiSpec.getHttpMethod().name());
        shape.put("path", apiSpec.getPath());
        shape.put("parameters", apiSpec.getParameters() == null ? Map.of() : new LinkedHashMap<>(apiSpec.getParameters()));
        shape.put("auth", apiSpec.getAuth() == null ? Map.of() : new LinkedHashMap<>(apiSpec.getAuth()));
        return shape;
    }

    private List<String> tags(ApiSpec apiSpec, ScenarioCategory category) {
        var tags = new ArrayList<String>();
        tags.add("api");
        tags.add("single");
        tags.add(category.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        tags.add(apiSpec.getModuleName());
        tags.add(apiSpec.getHttpMethod().name().toLowerCase(Locale.ROOT));
        if (category == ScenarioCategory.AUTHENTICATION_FAILURE || category == ScenarioCategory.PERMISSION_FAILURE) {
            tags.add("security");
        }
        return List.copyOf(tags);
    }

    private String dedupKey(String taskId, String apiSpecId, ScenarioIntent intent) {
        return String.join(
            ":",
            "phase5",
            taskId,
            TestCaseGenerationMode.SINGLE.name().toLowerCase(Locale.ROOT),
            apiSpecId,
            intent.category().name().toLowerCase(Locale.ROOT),
            intent.intentKey(),
            String.valueOf(intent.expectedStatus())
        );
    }

    private List<ScenarioCategory> requestedCategories(TestCaseGenerationRequest request) {
        if (request.scenarioFilters() == null || request.scenarioFilters().isEmpty()) {
            return List.copyOf(EnumSet.allOf(ScenarioCategory.class));
        }
        return List.copyOf(request.scenarioFilters());
    }

    private List<String> contextTags(ApiSpec apiSpec, List<ScenarioCategory> categories) {
        var tags = new ArrayList<String>();
        tags.add(apiSpec.getModuleName());
        categories.stream()
            .map(category -> category.name().toLowerCase(Locale.ROOT))
            .forEach(tags::add);
        return List.copyOf(tags);
    }

    private List<ParameterRef> parameters(ApiSpec apiSpec) {
        if (apiSpec.getParameters() == null || apiSpec.getParameters().isEmpty()) {
            return List.of();
        }
        var refs = new ArrayList<ParameterRef>();
        for (var sectionEntry : apiSpec.getParameters().entrySet()) {
            var section = sectionEntry.getKey();
            var sectionValue = sectionEntry.getValue();
            if (sectionValue instanceof Map<?, ?> parameterMap) {
                for (var parameterEntry : parameterMap.entrySet()) {
                    if (parameterEntry.getValue() instanceof Map<?, ?> metadata) {
                        refs.add(new ParameterRef(section, String.valueOf(parameterEntry.getKey()), toStringObjectMap(metadata)));
                    }
                }
            }
        }
        return List.copyOf(refs);
    }

    private ParameterRef firstConstrainedParameter(ApiSpec apiSpec, List<ParameterRef> parameters) {
        for (var parameter : parameters) {
            var merged = new LinkedHashMap<>(parameter.metadata());
            if (apiSpec.getConstraints() != null && apiSpec.getConstraints().get(parameter.name()) instanceof Map<?, ?> constraint) {
                merged.putAll(toStringObjectMap(constraint));
            }
            if (hasBoundaryConstraint(merged)) {
                return new ParameterRef(parameter.section(), parameter.name(), merged);
            }
        }
        return null;
    }

    private boolean hasBoundaryConstraint(Map<String, Object> metadata) {
        return metadata.containsKey("min")
            || metadata.containsKey("max")
            || metadata.containsKey("minimum")
            || metadata.containsKey("maximum")
            || metadata.containsKey("minLength")
            || metadata.containsKey("maxLength");
    }

    private boolean hasAuthMetadata(ApiSpec apiSpec) {
        return apiSpec.getAuth() != null && !apiSpec.getAuth().isEmpty() && !"none".equalsIgnoreCase(String.valueOf(apiSpec.getAuth().get("type")));
    }

    private boolean hasPermissionHints(ApiSpec apiSpec) {
        if (apiSpec.getAuth() == null || apiSpec.getAuth().isEmpty()) {
            return false;
        }
        return apiSpec.getAuth().containsKey("roles")
            || apiSpec.getAuth().containsKey("role")
            || apiSpec.getAuth().containsKey("requiredRole")
            || apiSpec.getAuth().containsKey("permissions")
            || apiSpec.getAuth().containsKey("scopes")
            || apiSpec.getAuth().containsKey("scope");
    }

    private Object invalidValueFor(ParameterRef parameter) {
        var type = String.valueOf(parameter.metadata().getOrDefault("type", "string")).toLowerCase(Locale.ROOT);
        return switch (type) {
            case "integer", "number", "long", "float", "double" -> "not-a-number";
            case "boolean" -> "not-a-boolean";
            default -> "";
        };
    }

    private Map<String, Object> constraintSnapshot(ParameterRef parameter) {
        var snapshot = new LinkedHashMap<String, Object>();
        for (var key : List.of("min", "max", "minimum", "maximum", "minLength", "maxLength", "type")) {
            if (parameter.metadata().containsKey(key)) {
                snapshot.put(key, parameter.metadata().get(key));
            }
        }
        return snapshot;
    }

    private Map<String, Object> toStringObjectMap(Map<?, ?> source) {
        var map = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }

    private String expectedResult(ScenarioIntent intent) {
        if (intent.category() == ScenarioCategory.HAPPY_PATH) {
            return "The API responds with HTTP " + intent.expectedStatus() + " and returns a successful response body.";
        }
        return "The API responds with HTTP " + intent.expectedStatus() + " and exposes a deterministic validation or authorization failure.";
    }

    private String scenarioName(ScenarioCategory category) {
        return category.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private int successStatus(HttpMethod method) {
        if (method == HttpMethod.POST) {
            return 201;
        }
        return 200;
    }

    private String rawQueryFor(ApiSpec apiSpec) {
        return "Generate SINGLE happy path test case drafts for "
            + apiSpec.getHttpMethod()
            + " "
            + apiSpec.getPath();
    }

    private int tokenBudget(TestCaseGenerationRequest request) {
        return request.tokenBudget() == null ? DEFAULT_TOKEN_BUDGET : request.tokenBudget();
    }

    private record ParameterRef(String section, String name, Map<String, Object> metadata) {
        boolean required() {
            return Boolean.TRUE.equals(metadata.get("required"));
        }
    }

    private record ScenarioPlan(
        List<ScenarioIntent> intents,
        Map<ScenarioCategory, String> skippedCategories,
        Map<ScenarioCategory, String> unsupportedCategories
    ) {
    }
}
