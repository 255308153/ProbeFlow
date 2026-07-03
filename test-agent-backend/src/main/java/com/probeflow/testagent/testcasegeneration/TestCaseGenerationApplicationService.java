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
            List.of(apiSpec.getModuleName(), ScenarioCategory.HAPPY_PATH.name().toLowerCase(Locale.ROOT)),
            tokenBudget(request)
        ));

        var draft = drafts.save(happyPathDraft(task.getTaskId(), task.getPromotionMode(), apiSpec, context, tokenBudget(request)));
        return new TestCaseGenerationResult(
            task.getTaskId(),
            request.sessionId(),
            request.generationMode(),
            List.of(apiSpec.getApiSpecId()),
            List.of(draft.getDraftId()),
            new TestCaseGenerationCounts(1, 0, 0, 0)
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
        if (request.scenarioFilters() != null
            && !request.scenarioFilters().isEmpty()
            && !request.scenarioFilters().contains(ScenarioCategory.HAPPY_PATH)) {
            throw new IllegalArgumentException("Issue 01 supports HAPPY_PATH scenarios only");
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

    private TestCaseDraft happyPathDraft(
        String taskId,
        PromotionMode promotionMode,
        ApiSpec apiSpec,
        ContextBundle context,
        int tokenBudget
    ) {
        var expectedStatus = successStatus(apiSpec.getHttpMethod());
        var draft = new TestCaseDraft();
        draft.setTaskId(taskId);
        draft.setSource(CaseSource.STRUCTURE);
        draft.setStage(CaseSource.STRUCTURE);
        draft.setStatus(DraftStatus.PENDING_REVIEW);
        draft.setPromotionMode(promotionMode);
        draft.setTargetApiSpecId(apiSpec.getApiSpecId());
        draft.setDedupKey(dedupKey(taskId, apiSpec.getApiSpecId(), expectedStatus));
        draft.setExpectedStatusCode(expectedStatus);
        draft.setDraftContent(happyPathContent(apiSpec, context, expectedStatus, tokenBudget));
        return draft;
    }

    private Map<String, Object> happyPathContent(
        ApiSpec apiSpec,
        ContextBundle context,
        int expectedStatus,
        int tokenBudget
    ) {
        var content = new LinkedHashMap<String, Object>();
        var titleSubject = StringUtils.hasText(apiSpec.getSummary())
            ? apiSpec.getSummary()
            : apiSpec.getHttpMethod() + " " + apiSpec.getPath();
        content.put("title", titleSubject + " happy path");
        content.put("description", "Verify the normal successful HTTP API behavior for " + apiSpec.getPath() + ".");
        content.put("preconditions", List.of("A valid API client is available", "Required request data is prepared"));
        content.put("steps", List.of(Map.of(
            "order", 1,
            "action", "Call HTTP API",
            "apiSpecId", apiSpec.getApiSpecId(),
            "method", apiSpec.getHttpMethod().name(),
            "path", apiSpec.getPath(),
            "requestShape", requestShape(apiSpec)
        )));
        content.put("expectedResult", "The API responds with HTTP " + expectedStatus + " and returns a successful response body.");
        content.put("requestShape", requestShape(apiSpec));
        content.put("expectedStatus", expectedStatus);
        content.put("scenarioCategory", ScenarioCategory.HAPPY_PATH.name());
        content.put("scenarioName", "happy-path");
        content.put("moduleName", apiSpec.getModuleName());
        content.put("tags", tags(apiSpec));
        content.put("generationMetadata", Map.of(
            "mode", TestCaseGenerationMode.SINGLE.name(),
            "generator", "deterministic-baseline",
            "stageProfile", STAGE_PROFILE,
            "apiSpecVersion", apiSpec.getVersion(),
            "contextBuilt", context.apiContext() != null,
            "contextBudgetRequested", tokenBudget,
            "source", CaseSource.STRUCTURE.name()
        ));
        return content;
    }

    private Map<String, Object> requestShape(ApiSpec apiSpec) {
        var shape = new LinkedHashMap<String, Object>();
        shape.put("method", apiSpec.getHttpMethod().name());
        shape.put("path", apiSpec.getPath());
        shape.put("parameters", apiSpec.getParameters() == null ? Map.of() : new LinkedHashMap<>(apiSpec.getParameters()));
        shape.put("auth", apiSpec.getAuth() == null ? Map.of() : new LinkedHashMap<>(apiSpec.getAuth()));
        return shape;
    }

    private List<String> tags(ApiSpec apiSpec) {
        var tags = new ArrayList<String>();
        tags.add("api");
        tags.add("single");
        tags.add("happy-path");
        tags.add(apiSpec.getModuleName());
        tags.add(apiSpec.getHttpMethod().name().toLowerCase(Locale.ROOT));
        return List.copyOf(tags);
    }

    private String dedupKey(String taskId, String apiSpecId, int expectedStatus) {
        return String.join(
            ":",
            "phase5",
            taskId,
            TestCaseGenerationMode.SINGLE.name().toLowerCase(Locale.ROOT),
            apiSpecId,
            ScenarioCategory.HAPPY_PATH.name().toLowerCase(Locale.ROOT),
            String.valueOf(expectedStatus)
        );
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
}
