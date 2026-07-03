package com.probeflow.testagent.testcasegeneration;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.memory.SessionMemoryService;
import com.probeflow.testagent.memory.SessionMemoryWriteRequest;
import com.probeflow.testagent.memory.TaskMemoryService;
import com.probeflow.testagent.memory.TaskMemoryWriteRequest;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcase.CaseSource;
import com.probeflow.testagent.testcasedraft.DraftStatus;
import com.probeflow.testagent.testcasedraft.PromotionMode;
import com.probeflow.testagent.testcasedraft.TestCaseDraftRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TestCaseGenerationApplicationServiceTests {

    @Autowired
    private TestCaseGenerationApplicationService generationService;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @Autowired
    private TestCaseDraftRepository drafts;

    @Autowired
    private KnowledgeIngestApplicationService knowledgeIngest;

    @Autowired
    private TaskMemoryService taskMemoryService;

    @Autowired
    private SessionMemoryService sessionMemoryService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void singleModeGeneratesPersistedHappyPathDraftThroughUnifiedContext() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));

        var result = generationService.generate(new TestCaseGenerationRequest(
            task.getTaskId(),
            "phase5-session-01",
            List.of(apiSpec.getApiSpecId()),
            TestCaseGenerationMode.SINGLE,
            List.of(ScenarioCategory.HAPPY_PATH),
            300
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.taskId()).isEqualTo(task.getTaskId());
        assertThat(result.sessionId()).isEqualTo("phase5-session-01");
        assertThat(result.generationMode()).isEqualTo(TestCaseGenerationMode.SINGLE);
        assertThat(result.targetApiSpecIds()).containsExactly(apiSpec.getApiSpecId());
        assertThat(result.createdDraftIds()).hasSize(1);
        assertThat(result.counts().created()).isEqualTo(1);
        assertThat(result.counts().updated()).isZero();
        assertThat(result.counts().skipped()).isZero();

        var draft = drafts.findById(result.createdDraftIds().getFirst()).orElseThrow();
        assertThat(draft.getTaskId()).isEqualTo(task.getTaskId());
        assertThat(draft.getTargetApiSpecId()).isEqualTo(apiSpec.getApiSpecId());
        assertThat(draft.getSource()).isEqualTo(CaseSource.STRUCTURE);
        assertThat(draft.getStage()).isEqualTo(CaseSource.STRUCTURE);
        assertThat(draft.getStatus()).isEqualTo(DraftStatus.PENDING_REVIEW);
        assertThat(draft.getPromotionMode()).isEqualTo(PromotionMode.MANUAL);
        assertThat(draft.getExpectedStatusCode()).isEqualTo(201);
        assertThat(draft.getDedupKey()).contains("phase5", "single", "happy_path", "201");

        var content = draft.getDraftContent();
        assertThat(content).containsEntry("scenarioCategory", ScenarioCategory.HAPPY_PATH.name());
        assertThat(content).containsEntry("expectedStatus", 201);
        assertThat(content).containsEntry("moduleName", "order");
        assertThat(content.get("title")).asString().contains("Create order", "happy path");
        assertThat(content.get("description")).asString().contains("/api/orders");
        assertThat(content.get("preconditions")).asList().isNotEmpty();
        assertThat(content.get("steps")).asList().hasSize(1);
        assertThat(content.get("tags")).asList().contains("api", "single", "happy-path", "order", "post");

        @SuppressWarnings("unchecked")
        var requestShape = (Map<String, Object>) content.get("requestShape");
        assertThat(requestShape).containsEntry("method", "POST");
        assertThat(requestShape).containsEntry("path", "/api/orders");
        assertThat(requestShape).containsKey("parameters");

        @SuppressWarnings("unchecked")
        var metadata = (Map<String, Object>) content.get("generationMetadata");
        assertThat(metadata).containsEntry("mode", "SINGLE");
        assertThat(metadata).containsEntry("generator", "deterministic-baseline");
        assertThat(metadata).containsEntry("stageProfile", "case_generation");
        assertThat(metadata).containsEntry("contextBuilt", true);
        assertThat(metadata).containsEntry("contextBudgetRequested", 300);
    }

    @Test
    void singleModePlansContractNegativeBoundaryAndAuthScenarioDraftsDeterministically() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));

        var result = generationService.generate(new TestCaseGenerationRequest(
            task.getTaskId(),
            "phase5-session-02",
            List.of(apiSpec.getApiSpecId()),
            TestCaseGenerationMode.SINGLE,
            List.of(
                ScenarioCategory.HAPPY_PATH,
                ScenarioCategory.MISSING_REQUIRED,
                ScenarioCategory.INVALID_VALUE,
                ScenarioCategory.BOUNDARY_VALUE,
                ScenarioCategory.AUTHENTICATION_FAILURE,
                ScenarioCategory.PERMISSION_FAILURE
            ),
            500
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.createdDraftIds()).hasSize(6);
        assertThat(result.generatedCategories()).containsExactly(
            ScenarioCategory.HAPPY_PATH,
            ScenarioCategory.MISSING_REQUIRED,
            ScenarioCategory.INVALID_VALUE,
            ScenarioCategory.BOUNDARY_VALUE,
            ScenarioCategory.AUTHENTICATION_FAILURE,
            ScenarioCategory.PERMISSION_FAILURE
        );
        assertThat(result.skippedCategories()).isEmpty();
        assertThat(result.unsupportedCategories()).isEmpty();
        assertThat(result.counts().created()).isEqualTo(6);

        var persistedDrafts = result.createdDraftIds().stream()
            .map(draftId -> drafts.findById(draftId).orElseThrow())
            .toList();

        assertThat(persistedDrafts).extracting(draft -> draft.getDraftContent().get("scenarioCategory"))
            .containsExactly(
                "HAPPY_PATH",
                "MISSING_REQUIRED",
                "INVALID_VALUE",
                "BOUNDARY_VALUE",
                "AUTHENTICATION_FAILURE",
                "PERMISSION_FAILURE"
            );
        assertThat(persistedDrafts).extracting("expectedStatusCode")
            .containsExactly(201, 400, 400, 400, 401, 403);

        var missingRequired = persistedDrafts.stream()
            .filter(draft -> draft.getDraftContent().get("scenarioCategory").equals("MISSING_REQUIRED"))
            .findFirst()
            .orElseThrow();
        assertThat(missingRequired.getDraftContent().get("validationHints")).asList()
            .anySatisfy(hint -> assertThat(hint).asString().contains("Missing parameter"));
        assertThat(missingRequired.getDraftContent()).containsEntry("priorityHint", "P1");
        assertThat(missingRequired.getDraftContent()).containsEntry("riskHint", "HIGH");

        @SuppressWarnings("unchecked")
        var boundaryShape = (Map<String, Object>) persistedDrafts.stream()
            .filter(draft -> draft.getDraftContent().get("scenarioCategory").equals("BOUNDARY_VALUE"))
            .findFirst()
            .orElseThrow()
            .getDraftContent()
            .get("requestShape");
        assertThat(boundaryShape).containsKey("mutation");

        @SuppressWarnings("unchecked")
        var authTags = (List<String>) persistedDrafts.stream()
            .filter(draft -> draft.getDraftContent().get("scenarioCategory").equals("AUTHENTICATION_FAILURE"))
            .findFirst()
            .orElseThrow()
            .getDraftContent()
            .get("tags");
        assertThat(authTags).contains("security", "authentication-failure");

        @SuppressWarnings("unchecked")
        var intentMetadata = (Map<String, Object>) ((Map<String, Object>) missingRequired.getDraftContent().get("generationMetadata"))
            .get("scenarioIntent");
        assertThat(intentMetadata).containsEntry("category", "MISSING_REQUIRED");
        assertThat(intentMetadata.get("intentKey")).asString().startsWith("missing-required-");
    }

    @Test
    void singleModeReportsSkippedAndUnsupportedScenarioCategories() {
        var apiSpec = apiSpecs.save(newMinimalApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));

        var result = generationService.generate(new TestCaseGenerationRequest(
            task.getTaskId(),
            "phase5-session-02-sparse",
            List.of(apiSpec.getApiSpecId()),
            TestCaseGenerationMode.SINGLE,
            List.of(
                ScenarioCategory.MISSING_REQUIRED,
                ScenarioCategory.BOUNDARY_VALUE,
                ScenarioCategory.AUTHENTICATION_FAILURE,
                ScenarioCategory.PERMISSION_FAILURE
            ),
            500
        ));

        assertThat(result.createdDraftIds()).isEmpty();
        assertThat(result.generatedCategories()).isEmpty();
        assertThat(result.skippedCategories()).containsKeys(ScenarioCategory.MISSING_REQUIRED, ScenarioCategory.BOUNDARY_VALUE);
        assertThat(result.unsupportedCategories()).containsKeys(
            ScenarioCategory.AUTHENTICATION_FAILURE,
            ScenarioCategory.PERMISSION_FAILURE
        );
        assertThat(result.counts().created()).isZero();
        assertThat(result.counts().skipped()).isEqualTo(4);
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).asString().contains("LOW_CONFIDENCE_CONTEXT"));
    }

    @Test
    void generationUsesKnowledgeAndMemoryContextWithCitationsAndWarnings() {
        var apiSpec = apiSpecs.save(newPaymentApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));

        knowledgeIngest.ingest(new KnowledgeIngestRequest(
            "Payment tenant bootstrap rule",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Payment tenant bootstrap

                POST /api/orders/{orderId}/pay requires tenant bootstrap before payment auth.
                """,
            DocumentSourceType.WIKI,
            "wiki/payment-tenant-bootstrap.md",
            DocumentType.DOMAIN_RULE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment"),
            List.of("case_generation"),
            Map.of()
        ));
        taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            task.getTaskId(),
            MemoryScopeType.FAILURE_PATTERN,
            "PAY_401 occurred when bootstrap was skipped",
            "Known failure pattern: PAY_401 occurs when tenant bootstrap is missing before payment auth.",
            List.of("payment", "auth", "tenant"),
            MemorySourceType.OBSERVATION,
            "task-memory-pay-401",
            0.91f,
            "case_generation",
            Map.of("module", "payment", "apiPath", "/api/orders/{orderId}/pay", "errorCode", "PAY_401"),
            null
        ));
        taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            task.getTaskId(),
            MemoryScopeType.PROJECT_KNOWLEDGE,
            "Task note says bootstrap is not required",
            "Tenant bootstrap is not required before payment auth for this observed task path.",
            List.of("payment", "tenant", "auth"),
            MemorySourceType.OBSERVATION,
            "task-memory-conflict",
            0.84f,
            "case_generation",
            Map.of("module", "payment", "apiPath", "/api/orders/{orderId}/pay"),
            null
        ));
        sessionMemoryService.writeSessionMemory(new SessionMemoryWriteRequest(
            "phase5-session-context",
            MemoryScopeType.TESTING_PATTERN,
            "Review payment regression smoke",
            "Payment regression smoke should include tenant bootstrap and auth failure coverage.",
            List.of("payment", "regression"),
            MemorySourceType.USER_FEEDBACK,
            "session-memory-regression",
            0.80f,
            Map.of("module", "payment"),
            3600L
        ));

        var result = generationService.generate(new TestCaseGenerationRequest(
            task.getTaskId(),
            "phase5-session-context",
            List.of(apiSpec.getApiSpecId()),
            TestCaseGenerationMode.SINGLE,
            List.of(
                ScenarioCategory.BUSINESS_RULE,
                ScenarioCategory.HISTORICAL_FAILURE,
                ScenarioCategory.REGRESSION_RISK
            ),
            600
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.createdDraftIds()).hasSize(3);
        assertThat(result.generatedCategories()).containsExactly(
            ScenarioCategory.BUSINESS_RULE,
            ScenarioCategory.HISTORICAL_FAILURE,
            ScenarioCategory.REGRESSION_RISK
        );
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).asString().contains("CONTEXT_CONFLICT"));

        var persistedDrafts = result.createdDraftIds().stream()
            .map(draftId -> drafts.findById(draftId).orElseThrow())
            .toList();

        var businessRule = persistedDrafts.stream()
            .filter(draft -> draft.getDraftContent().get("scenarioCategory").equals("BUSINESS_RULE"))
            .findFirst()
            .orElseThrow();
        assertThat(businessRule.getDraftContent()).containsEntry("constraintSource", "KNOWLEDGE");
        assertThat(businessRule.getDraftContent().get("contextCitations")).asList()
            .anySatisfy(citation -> assertThat(citation).asString().contains("knowledge_chunk", "wiki/payment-tenant-bootstrap.md"));

        var historicalFailure = persistedDrafts.stream()
            .filter(draft -> draft.getDraftContent().get("scenarioCategory").equals("HISTORICAL_FAILURE"))
            .findFirst()
            .orElseThrow();
        assertThat(historicalFailure.getDraftContent()).containsEntry("constraintSource", "MEMORY");
        assertThat(historicalFailure.getDraftContent().get("contextCitations")).asList()
            .anySatisfy(citation -> assertThat(citation).asString().contains("task_memory", "task-memory-pay-401"));

        var regressionRisk = persistedDrafts.stream()
            .filter(draft -> draft.getDraftContent().get("scenarioCategory").equals("REGRESSION_RISK"))
            .findFirst()
            .orElseThrow();
        assertThat(regressionRisk.getDraftContent().get("tags")).asList().contains("memory", "regression-risk");

        @SuppressWarnings("unchecked")
        var metadata = (Map<String, Object>) businessRule.getDraftContent().get("generationMetadata");
        assertThat(metadata).containsEntry("constraintSource", "KNOWLEDGE");
        assertThat(metadata).containsEntry("contextConflictCount", 1);

        assertThat(businessRule.getDraftContent().get("contextWarnings")).asList()
            .anySatisfy(warning -> assertThat(warning).asString().contains("CONTEXT_CONFLICT"));
    }

    @Test
    void repeatedSingleGenerationSuppressesDuplicateDraftsByDedupKey() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var request = new TestCaseGenerationRequest(
            task.getTaskId(),
            "phase5-session-04",
            List.of(apiSpec.getApiSpecId()),
            TestCaseGenerationMode.SINGLE,
            List.of(ScenarioCategory.HAPPY_PATH, ScenarioCategory.MISSING_REQUIRED),
            300
        );

        var first = generationService.generate(request);
        var second = generationService.generate(request);

        assertThat(first.counts().created()).isEqualTo(2);
        assertThat(second.createdDraftIds()).isEmpty();
        assertThat(second.counts().created()).isZero();
        assertThat(second.counts().updated()).isZero();
        assertThat(second.counts().duplicateSuppressed()).isEqualTo(2);
        assertThat(drafts.findAll()).hasSize(2);
    }

    @Test
    void regenerationUpdatesCompatiblePendingDraftWhenContentChanges() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var request = new TestCaseGenerationRequest(
            task.getTaskId(),
            "phase5-session-04-update",
            List.of(apiSpec.getApiSpecId()),
            TestCaseGenerationMode.SINGLE,
            List.of(ScenarioCategory.HAPPY_PATH),
            300
        );

        var first = generationService.generate(request);
        var draftId = first.createdDraftIds().getFirst();
        var draft = drafts.findById(draftId).orElseThrow();
        var editedContent = new java.util.LinkedHashMap<>(draft.getDraftContent());
        editedContent.put("description", "stale generated description");
        draft.setDraftContent(editedContent);
        drafts.save(draft);
        entityManager.flush();

        var second = generationService.generate(request);

        assertThat(second.createdDraftIds()).isEmpty();
        assertThat(second.counts().updated()).isEqualTo(1);
        assertThat(second.counts().duplicateSuppressed()).isZero();
        assertThat(drafts.findAll()).hasSize(1);
        assertThat(drafts.findById(draftId).orElseThrow().getDraftContent().get("description"))
            .asString()
            .contains("normal successful HTTP API behavior");
    }

    @Test
    void regenerationSkipsPromotedDraftsWithoutOverwriting() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var request = new TestCaseGenerationRequest(
            task.getTaskId(),
            "phase5-session-04-promoted",
            List.of(apiSpec.getApiSpecId()),
            TestCaseGenerationMode.SINGLE,
            List.of(ScenarioCategory.HAPPY_PATH),
            300
        );

        var first = generationService.generate(request);
        var draft = drafts.findById(first.createdDraftIds().getFirst()).orElseThrow();
        var protectedContent = new java.util.LinkedHashMap<>(draft.getDraftContent());
        protectedContent.put("description", "human approved protected content");
        draft.setDraftContent(protectedContent);
        draft.setStatus(DraftStatus.PROMOTED);
        draft.setPromotedCaseId("case-protected-01");
        drafts.save(draft);
        entityManager.flush();

        var second = generationService.generate(request);

        assertThat(second.createdDraftIds()).isEmpty();
        assertThat(second.counts().skipped()).isEqualTo(1);
        assertThat(second.counts().updated()).isZero();
        assertThat(second.counts().duplicateSuppressed()).isZero();
        var loaded = drafts.findById(draft.getDraftId()).orElseThrow();
        assertThat(loaded.getPromotedCaseId()).isEqualTo("case-protected-01");
        assertThat(loaded.getDraftContent().get("description")).isEqualTo("human approved protected content");
    }

    @Test
    void contextDerivedScenariosUseDedupOnRepeatedGeneration() {
        var apiSpec = apiSpecs.save(newPaymentApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        knowledgeIngest.ingest(new KnowledgeIngestRequest(
            "Payment business rule",
            KnowledgeContentFormat.MARKDOWN,
            "POST /api/orders/{orderId}/pay requires tenant bootstrap.",
            DocumentSourceType.WIKI,
            "wiki/payment-business-rule.md",
            DocumentType.DOMAIN_RULE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment"),
            List.of("case_generation"),
            Map.of()
        ));
        var request = new TestCaseGenerationRequest(
            task.getTaskId(),
            "phase5-session-04-context",
            List.of(apiSpec.getApiSpecId()),
            TestCaseGenerationMode.SINGLE,
            List.of(ScenarioCategory.BUSINESS_RULE),
            600
        );

        var first = generationService.generate(request);
        var second = generationService.generate(request);

        assertThat(first.counts().created()).isEqualTo(1);
        assertThat(second.counts().duplicateSuppressed()).isEqualTo(1);
        assertThat(drafts.findAll()).hasSize(1);
    }

    private ApiSpec newApiSpec() {
        var apiSpec = new ApiSpec();
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
        apiSpec.setConstraints(Map.of(
            "quantity", Map.of("min", 1, "max", 99)
        ));
        apiSpec.setAuth(Map.of(
            "type", "bearer",
            "header", "Authorization",
            "roles", List.of("ORDER_MANAGER")
        ));
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

    private ApiSpec newMinimalApiSpec() {
        var apiSpec = new ApiSpec();
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("catalog");
        apiSpec.setHttpMethod(HttpMethod.GET);
        apiSpec.setPath("/api/catalog/ping");
        apiSpec.setSummary("Catalog ping");
        apiSpec.setParameters(Map.of());
        apiSpec.setConstraints(Map.of());
        apiSpec.setAuth(Map.of());
        apiSpec.setSourceType(ApiSpecSourceType.MANUAL);
        apiSpec.setSourceRef("manual://catalog-ping");
        apiSpec.setSourceLocation(Map.of());
        apiSpec.setVersion(1);
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(false);
        apiSpec.setValidationReady(false);
        apiSpec.setAuthReady(false);
        apiSpec.setKnowledgeContextReady(false);
        return apiSpec;
    }

    private ApiSpec newPaymentApiSpec() {
        var apiSpec = new ApiSpec();
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("payment");
        apiSpec.setHttpMethod(HttpMethod.POST);
        apiSpec.setPath("/api/orders/{orderId}/pay");
        apiSpec.setSummary("Pay order");
        apiSpec.setDescription("Pay an order by id");
        apiSpec.setOperationId("payOrder");
        apiSpec.setParameters(Map.of(
            "path", Map.of("orderId", Map.of("type", "string", "required", true)),
            "body", Map.of("paymentToken", Map.of("type", "string", "required", true))
        ));
        apiSpec.setConstraints(Map.of(
            "paymentToken", Map.of("minLength", 12)
        ));
        apiSpec.setAuth(Map.of(
            "type", "bearer",
            "roles", List.of("PAYMENT_OPERATOR")
        ));
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("openapi://orders.yaml#/paths/~1api~1orders~1{orderId}~1pay/post");
        apiSpec.setSourceLocation(Map.of("line", 88));
        apiSpec.setVersion(4);
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(false);
        return apiSpec;
    }

    private Task newTask(String apiSpecId) {
        var task = new Task();
        task.setTaskType(TaskType.API_ANALYSIS);
        task.setTaskName("Generate order API cases");
        task.setStatus(TaskStatus.COMPLETED);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("phase5-issue-01");
        task.setTargetApiSpecIds(List.of(apiSpecId));
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.NOT_REQUIRED);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase5-test");
        task.setMetadata(Map.of("phase", "5", "issue", "01"));
        return task;
    }
}
