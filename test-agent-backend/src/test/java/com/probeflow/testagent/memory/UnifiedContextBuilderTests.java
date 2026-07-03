package com.probeflow.testagent.memory;

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
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
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
class UnifiedContextBuilderTests {

    @Autowired
    private UnifiedContextBuilder unifiedContextBuilder;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @Autowired
    private SessionMemoryService sessionMemoryService;

    @Autowired
    private TaskMemoryService taskMemoryService;

    @Autowired
    private KnowledgeIngestApplicationService knowledgeIngest;

    @Autowired
    private MemoryRefineryService memoryRefineryService;

    @Test
    void buildsSectionedContextBundleFromAllAvailableSources() {
        var apiSpec = apiSpecs.save(newApiSpec("/api/orders/{orderId}/pay", HttpMethod.POST));
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));

        sessionMemoryService.writeSessionMemory(new SessionMemoryWriteRequest(
            "phase4-session-06",
            MemoryScopeType.PROJECT_KNOWLEDGE,
            "Remember sandbox tenant bootstrap",
            "Recent session decision keeps sandbox tenant bootstrap enabled.",
            List.of("tenant", "sandbox"),
            MemorySourceType.USER_FEEDBACK,
            "session-ctx-1",
            0.82f,
            Map.of("module", "payment"),
            3600L
        ));
        sessionMemoryService.writeSessionMemory(new SessionMemoryWriteRequest(
            "phase4-session-06",
            MemoryScopeType.PREFERENCE,
            "Prefer compact failure notes",
            "Keep failure notes compact and action-oriented during this session.",
            List.of("preference", "report"),
            MemorySourceType.USER_FEEDBACK,
            "session-ctx-2",
            0.79f,
            Map.of("module", "payment"),
            3600L
        ));

        taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            task.getTaskId(),
            MemoryScopeType.FAILURE_PATTERN,
            "PAY_401 happened during payment execution",
            "Observed PAY_401 when tenant bootstrap was skipped.",
            List.of("payment", "auth", "pay_401"),
            MemorySourceType.OBSERVATION,
            "task-ctx-1",
            0.9f,
            "failure_analysis",
            Map.of("module", "payment", "errorCode", "PAY_401"),
            null
        ));
        taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            task.getTaskId(),
            MemoryScopeType.TESTING_PATTERN,
            "Prepare idempotency key before calling pay API",
            "Execution preparation should generate an idempotency key before POST /pay.",
            List.of("payment", "setup"),
            MemorySourceType.MANUAL,
            "task-ctx-2",
            0.81f,
            "execution_preparation",
            Map.of("module", "payment"),
            null
        ));

        knowledgeIngest.ingest(new KnowledgeIngestRequest(
            "Payment API auth note",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Payment auth

                POST /api/orders/{orderId}/pay requires tenant bootstrap and signature validation.
                """,
            DocumentSourceType.WIKI,
            "wiki/payment-auth-note.md",
            DocumentType.API_NOTE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment", "auth", "tenant"),
            List.of("failure_analysis"),
            Map.of()
        ));

        memoryRefineryService.refine(new MemoryCandidateRequest(
            "Tenant bootstrap prevents PAY_401",
            "Bootstrap tenant context before auth checks to avoid PAY_401 during payment execution.",
            MemorySourceType.OBSERVATION,
            "ltm-ctx-1",
            task.getTaskId(),
            List.of("payment", "auth", "tenant"),
            0.92f,
            "Observed PAY_401 disappears after tenant bootstrap.",
            Map.of(
                "systemName", "order-platform",
                "module", "payment",
                "apiPath", "/api/orders/{orderId}/pay",
                "errorCode", "PAY_401"
            )
        ));

        var bundle = unifiedContextBuilder.build(new UnifiedContextQuery(
            task.getTaskId(),
            "phase4-session-06",
            apiSpec.getApiSpecId(),
            null,
            "failure_analysis",
            "payment auth failure and tenant bootstrap",
            null,
            null,
            null,
            "PAY_401",
            List.of("payment", "auth", "tenant"),
            400
        ));

        assertThat(bundle.apiContext()).isNotNull();
        assertThat(bundle.apiContext().path()).isEqualTo("/api/orders/{orderId}/pay");
        assertThat(bundle.taskState()).isNotNull();
        assertThat(bundle.taskState().taskId()).isEqualTo(task.getTaskId());
        assertThat(bundle.sessionContext()).extracting(SessionMemoryView::summary)
            .containsExactly("Remember sandbox tenant bootstrap", "Prefer compact failure notes");
        assertThat(bundle.taskMemory()).hasSize(2);
        assertThat(bundle.taskMemory().getFirst().lifecycleStage()).isEqualTo("failure_analysis");
        assertThat(bundle.knowledgeContext().apiNotes()).hasSize(1);
        assertThat(bundle.longTermMemoryContext().hits()).hasSize(1);
        assertThat(bundle.constraints()).containsKeys("apiConstraints", "apiAuth", "stageProfile");
        assertThat(bundle.citations()).extracting(ContextCitation::citationType)
            .contains("session_memory", "task_memory", "knowledge_chunk", "long_term_memory");
        assertThat(bundle.citations()).extracting(ContextCitation::sourceRef)
            .contains("session-ctx-1", "task-ctx-1", "wiki/payment-auth-note.md", "ltm-ctx-1");
        assertThat(bundle.coverage().hasApiContext()).isTrue();
        assertThat(bundle.coverage().hasTaskState()).isTrue();
        assertThat(bundle.coverage().hasSessionContext()).isTrue();
        assertThat(bundle.coverage().hasTaskMemory()).isTrue();
        assertThat(bundle.coverage().hasKnowledgeContext()).isTrue();
        assertThat(bundle.coverage().hasLongTermMemory()).isTrue();
        assertThat(bundle.budget().requestedTokenBudget()).isEqualTo(400);
        assertThat(bundle.budget().totalEstimatedTokens()).isGreaterThan(0);
    }

    @Test
    void degradesGracefullyWhenMemoryAndKnowledgeSourcesAreEmptyAndSupportsDirectApiSpecInput() {
        var task = tasks.save(newTask("api-spec-direct"));
        var apiSpec = newApiSpec("/api/orders/{orderId}/pay", HttpMethod.POST);

        var bundle = unifiedContextBuilder.build(new UnifiedContextQuery(
            task.getTaskId(),
            "missing-session",
            null,
            apiSpec,
            "case_generation",
            "payment setup guidance",
            null,
            null,
            null,
            null,
            List.of("payment"),
            200
        ));

        assertThat(bundle.apiContext()).isNotNull();
        assertThat(bundle.apiContext().path()).isEqualTo("/api/orders/{orderId}/pay");
        assertThat(bundle.taskState()).isNotNull();
        assertThat(bundle.sessionContext()).isEmpty();
        assertThat(bundle.taskMemory()).isEmpty();
        assertThat(bundle.knowledgeContext().isEmpty()).isTrue();
        assertThat(bundle.longTermMemoryContext().isEmpty()).isTrue();
        assertThat(bundle.citations()).isEmpty();
        assertThat(bundle.coverage().hasSessionContext()).isFalse();
        assertThat(bundle.coverage().hasTaskMemory()).isFalse();
        assertThat(bundle.coverage().hasKnowledgeContext()).isFalse();
        assertThat(bundle.coverage().hasLongTermMemory()).isFalse();
        assertThat(bundle.coverage().lowConfidence()).isTrue();
        assertThat(bundle.budget().requestedTokenBudget()).isEqualTo(200);
    }

    private Task newTask(String apiSpecId) {
        var task = new Task();
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Payment auth failure investigation");
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("issue-06");
        task.setTargetApiSpecIds(List.of(apiSpecId));
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.PENDING);
        task.setPriority(TaskPriority.HIGH);
        task.setCreator("phase4-test");
        task.setMetadata(Map.of("goal", "stabilize payment auth context"));
        return task;
    }

    private ApiSpec newApiSpec(String path, HttpMethod httpMethod) {
        var apiSpec = new ApiSpec();
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("payment");
        apiSpec.setHttpMethod(httpMethod);
        apiSpec.setPath(path);
        apiSpec.setSummary("Pay order");
        apiSpec.setDescription("Pay an order by id.");
        apiSpec.setOperationId("payOrder");
        apiSpec.setParameters(Map.of("orderId", Map.of("type", "string")));
        apiSpec.setConstraints(Map.of("requiresIdempotencyKey", true));
        apiSpec.setAuth(Map.of("required", true));
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("orders-openapi.yaml");
        apiSpec.setSourceLocation(Map.of("line", 12));
        apiSpec.setKnowledgeContextReady(false);
        apiSpec.setPresentInLatestAnalysis(true);
        return apiSpec;
    }
}
