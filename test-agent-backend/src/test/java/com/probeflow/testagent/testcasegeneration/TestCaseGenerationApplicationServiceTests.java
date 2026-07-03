package com.probeflow.testagent.testcasegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
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
            "header", "Authorization"
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
