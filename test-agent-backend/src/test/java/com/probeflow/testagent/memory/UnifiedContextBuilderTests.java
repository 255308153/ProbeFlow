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

    @Autowired
    private MemoryUsageRecordRepository usageRecords;

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

    @Test
    void detectsConflictsBetweenKnowledgeAndMemoryAndPreservesBothSides() {
        var apiSpec = apiSpecs.save(newApiSpec("/api/orders/{orderId}/pay", HttpMethod.POST));
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));

        knowledgeIngest.ingest(new KnowledgeIngestRequest(
            "Payment bootstrap rule",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Bootstrap rule

                POST /api/orders/{orderId}/pay requires tenant bootstrap before payment auth.
                """,
            DocumentSourceType.WIKI,
            "wiki/payment-bootstrap-rule.md",
            DocumentType.API_NOTE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment", "tenant", "auth"),
            List.of("failure_analysis"),
            Map.of()
        ));

        taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            task.getTaskId(),
            MemoryScopeType.PROJECT_KNOWLEDGE,
            "Task note says bootstrap is not required",
            "Tenant bootstrap is not required before payment auth for this observed task path.",
            List.of("payment", "tenant", "auth"),
            MemorySourceType.OBSERVATION,
            "task-conflict-1",
            0.84f,
            "failure_analysis",
            Map.of("module", "payment", "apiPath", "/api/orders/{orderId}/pay"),
            null
        ));

        var bundle = unifiedContextBuilder.build(new UnifiedContextQuery(
            task.getTaskId(),
            null,
            apiSpec.getApiSpecId(),
            null,
            "failure_analysis",
            "tenant bootstrap payment auth",
            null,
            null,
            null,
            null,
            List.of("payment", "tenant", "auth"),
            300
        ));

        assertThat(bundle.conflicts()).hasSize(1);
        var conflict = bundle.conflicts().getFirst();
        assertThat(conflict.conflictType()).isEqualTo("requirement-conflict");
        assertThat(conflict.preferredSourceType()).isEqualTo("task_memory");
        assertThat(conflict.knowledgeSide().sourceRef()).isEqualTo("wiki/payment-bootstrap-rule.md");
        assertThat(conflict.memorySide().sourceRef()).isEqualTo("task-conflict-1");
        assertThat(conflict.knowledgeSide().sourceId()).isNotBlank();
        assertThat(conflict.memorySide().sourceId()).isNotBlank();
    }

    @Test
    void prunesContextDeterministicallyByPriorityWhenBudgetIsTight() {
        var apiSpec = apiSpecs.save(newApiSpec("/api/orders/{orderId}/pay", HttpMethod.POST));
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));

        taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            task.getTaskId(),
            MemoryScopeType.FAILURE_PATTERN,
            "PAY_401 observed",
            "Current task observed PAY_401 after skipping tenant bootstrap.",
            List.of("payment", "tenant", "auth"),
            MemorySourceType.OBSERVATION,
            "task-prune-1",
            0.93f,
            "failure_analysis",
            Map.of("module", "payment", "errorCode", "PAY_401"),
            null
        ));

        sessionMemoryService.writeSessionMemory(new SessionMemoryWriteRequest(
            "phase4-session-07",
            MemoryScopeType.PREFERENCE,
            "Very long session preference",
            "Keep the analysis narrative extremely detailed with multiple historical notes and extra explanation that should be pruned first.",
            List.of("report", "preference"),
            MemorySourceType.USER_FEEDBACK,
            "session-prune-1",
            0.78f,
            Map.of("module", "payment"),
            3600L
        ));

        knowledgeIngest.ingest(new KnowledgeIngestRequest(
            "Payment auth rule",
            KnowledgeContentFormat.MARKDOWN,
            """
                # Rule

                POST /api/orders/{orderId}/pay requires tenant bootstrap before pay.
                """,
            DocumentSourceType.WIKI,
            "wiki/payment-auth-rule.md",
            DocumentType.API_NOTE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment", "tenant", "auth"),
            List.of("failure_analysis"),
            Map.of()
        ));

        memoryRefineryService.refine(new MemoryCandidateRequest(
            "PAY_401 risk hint",
            "PAY_401 means tenant bootstrap skipped.",
            MemorySourceType.OBSERVATION,
            "ltm-risk-1",
            task.getTaskId(),
            List.of("payment", "tenant", "auth"),
            0.91f,
            "PAY_401 failure pattern.",
            Map.of(
                "systemName", "order-platform",
                "module", "payment",
                "apiPath", "/api/orders/{orderId}/pay",
                "errorCode", "PAY_401"
            )
        ));
        memoryRefineryService.refine(new MemoryCandidateRequest(
            "Low-signal project note",
            "General reminder for payment team documentation cleanup and naming consistency across several unrelated notes.",
            MemorySourceType.MANUAL,
            "ltm-risk-2",
            task.getTaskId(),
            List.of("payment"),
            0.60f,
            "Low-signal project knowledge note.",
            Map.of(
                "systemName", "order-platform",
                "module", "payment",
                "apiPath", "/api/orders/{orderId}/pay"
            )
        ));

        var bundle = unifiedContextBuilder.build(new UnifiedContextQuery(
            task.getTaskId(),
            "phase4-session-07",
            apiSpec.getApiSpecId(),
            null,
            "failure_analysis",
            "payment auth tenant bootstrap",
            null,
            null,
            null,
            "PAY_401",
            List.of("payment", "tenant", "auth"),
            20
        ));

        assertThat(bundle.taskMemory()).hasSize(1);
        assertThat(bundle.knowledgeContext().apiNotes()).hasSize(1);
        assertThat(bundle.sessionContext()).isEmpty();
        assertThat(bundle.longTermMemoryContext().hits()).extracting(LongTermMemoryRetrievalHit::scopeType)
            .containsOnly(MemoryScopeType.FAILURE_PATTERN);
        assertThat(bundle.budget().pruned()).isTrue();
        assertThat(bundle.budget().originalEstimatedTokens()).isGreaterThan(bundle.budget().totalEstimatedTokens());
        assertThat(bundle.budget().totalEstimatedTokens()).isLessThanOrEqualTo(bundle.budget().requestedTokenBudget());
    }

    @Test
    void recordsLongTermMemoryUsageForContextCitationsAndConsumer() {
        var apiSpec = apiSpecs.save(newApiSpec("/api/orders/{orderId}/pay", HttpMethod.POST));
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var refined = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Tenant bootstrap prevents PAY_401",
            "Bootstrap tenant context before auth checks to avoid PAY_401 during payment execution.",
            MemorySourceType.OBSERVATION,
            "ltm-usage-1",
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

        var query = new UnifiedContextQuery(
            task.getTaskId(),
            null,
            apiSpec.getApiSpecId(),
            null,
            "failure_analysis",
            "payment auth failure and tenant bootstrap",
            null,
            null,
            null,
            "PAY_401",
            List.of("payment", "auth", "tenant"),
            400,
            MemoryUsageConsumer.PLANNER,
            "planner:context-input-1"
        );
        var bundle = unifiedContextBuilder.build(query);
        var citation = bundle.citations().stream()
            .filter(item -> "long_term_memory".equals(item.citationType()))
            .findFirst()
            .orElseThrow();
        var firstRecords = usageRecords.findAllByTaskIdOrderByCreatedAtAscUsageIdAsc(task.getTaskId());

        assertThat(citation.sourceId()).isEqualTo(refined.memory().memoryId());
        assertThat(firstRecords).hasSize(1);
        var usage = firstRecords.getFirst();
        assertThat(usage.getMemoryId()).isEqualTo(refined.memory().memoryId());
        assertThat(usage.getTaskId()).isEqualTo(task.getTaskId());
        assertThat(usage.getStageProfile()).isEqualTo("failure_analysis");
        assertThat(usage.getConsumer()).isEqualTo(MemoryUsageConsumer.PLANNER);
        assertThat(usage.getSourceRef()).isEqualTo("planner:context-input-1");
        assertThat(usage.getCitationType()).isEqualTo("long_term_memory");
        assertThat(usage.getCitationSourceId()).isEqualTo(citation.sourceId());
        assertThat(usage.getCitationSourceRef()).isEqualTo("ltm-usage-1");
        assertThat(usage.getScore()).isEqualTo(citation.score());
        assertThat(usage.getConfidence()).isEqualTo(refined.memory().confidence());
        assertThat(usage.getLowConfidence()).isEqualTo(bundle.longTermMemoryContext().hits().getFirst().lowConfidence());
        assertThat(usage.getMatchReasons()).contains("structure-match", "tag-match", "stage-fit");
        assertThat(usage.getMetadata())
            .containsEntry("memoryScopeType", MemoryScopeType.FAILURE_PATTERN.name())
            .containsEntry("memorySourceType", MemorySourceType.OBSERVATION.name())
            .containsEntry("apiSpecId", apiSpec.getApiSpecId())
            .containsEntry("errorCode", "PAY_401");

        unifiedContextBuilder.build(query);
        assertThat(usageRecords.findAllByTaskIdOrderByCreatedAtAscUsageIdAsc(task.getTaskId()))
            .hasSize(2)
            .extracting(MemoryUsageRecord::getUsageId)
            .doesNotHaveDuplicates();
    }

    @Test
    void archivedAndInactiveMemoriesDoNotProduceUsageRecords() {
        var apiSpec = apiSpecs.save(newApiSpec("/api/orders/{orderId}/pay", HttpMethod.POST));
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var archived = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Archived PAY_401 hint",
            "Archived tenant bootstrap hint should not be recalled.",
            MemorySourceType.OBSERVATION,
            "ltm-usage-archived",
            task.getTaskId(),
            List.of("payment", "auth", "tenant"),
            0.92f,
            "Archived evidence.",
            Map.of(
                "systemName", "order-platform",
                "module", "payment",
                "apiPath", "/api/orders/{orderId}/pay",
                "errorCode", "PAY_401"
            )
        ));
        memoryRefineryService.archiveMemory(archived.memory().memoryId());
        var inactive = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Inactive PAY_401 hint",
            "Inactive tenant bootstrap hint should not be recalled.",
            MemorySourceType.OBSERVATION,
            "ltm-usage-inactive",
            task.getTaskId(),
            List.of("payment", "auth", "tenant"),
            0.91f,
            "Inactive evidence.",
            Map.of(
                "systemName", "order-platform",
                "module", "payment",
                "apiPath", "/api/orders/{orderId}/pay",
                "errorCode", "PAY_401"
            )
        ));
        memoryRefineryService.deactivateMemory(inactive.memory().memoryId());

        var bundle = unifiedContextBuilder.build(new UnifiedContextQuery(
            task.getTaskId(),
            null,
            apiSpec.getApiSpecId(),
            null,
            "failure_analysis",
            "payment auth failure and tenant bootstrap",
            null,
            null,
            null,
            "PAY_401",
            List.of("payment", "auth", "tenant"),
            400,
            MemoryUsageConsumer.FAILURE_ANALYSIS,
            "failure-analysis:context-input-1"
        ));

        assertThat(bundle.longTermMemoryContext().hits()).isEmpty();
        assertThat(bundle.citations()).noneMatch(citation -> "long_term_memory".equals(citation.citationType()));
        assertThat(usageRecords.findAllByTaskIdOrderByCreatedAtAscUsageIdAsc(task.getTaskId())).isEmpty();
    }

    @Test
    void infersUsageConsumerFromStageProfileWhenConsumerIsNotProvided() {
        var apiSpec = apiSpecs.save(newApiSpec("/api/orders/{orderId}/pay", HttpMethod.POST));
        var failureTask = tasks.save(newTask(apiSpec.getApiSpecId()));
        memoryRefineryService.refine(new MemoryCandidateRequest(
            "PAY_401 failure analysis memory",
            "PAY_401 failure analysis should check tenant bootstrap before auth.",
            MemorySourceType.OBSERVATION,
            "ltm-usage-infer-failure",
            failureTask.getTaskId(),
            List.of("payment", "auth", "tenant"),
            0.92f,
            "Observed PAY_401 failure analysis pattern.",
            Map.of(
                "systemName", "order-platform",
                "module", "payment",
                "apiPath", "/api/orders/{orderId}/pay",
                "errorCode", "PAY_401"
            )
        ));

        unifiedContextBuilder.build(new UnifiedContextQuery(
            failureTask.getTaskId(),
            null,
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

        var caseTask = tasks.save(newTask(apiSpec.getApiSpecId()));
        memoryRefineryService.refine(new MemoryCandidateRequest(
            "Payment case generation checklist",
            "Test case generation should assert idempotency key and tenant bootstrap preconditions.",
            MemorySourceType.MANUAL,
            "ltm-usage-infer-case-generation",
            caseTask.getTaskId(),
            List.of("payment", "case_generation", "precondition"),
            0.9f,
            "Human-approved checklist for payment test case generation.",
            Map.of(
                "systemName", "order-platform",
                "module", "payment",
                "apiPath", "/api/orders/{orderId}/pay"
            )
        ));

        unifiedContextBuilder.build(new UnifiedContextQuery(
            caseTask.getTaskId(),
            null,
            apiSpec.getApiSpecId(),
            null,
            "case_generation",
            "payment test case generation checklist",
            null,
            null,
            null,
            null,
            List.of("payment", "case_generation", "precondition"),
            400
        ));

        var failureUsage = usageRecords.findAllByTaskIdOrderByCreatedAtAscUsageIdAsc(failureTask.getTaskId()).getFirst();
        var caseUsage = usageRecords.findAllByTaskIdOrderByCreatedAtAscUsageIdAsc(caseTask.getTaskId()).getFirst();
        assertThat(failureUsage.getConsumer()).isEqualTo(MemoryUsageConsumer.FAILURE_ANALYSIS);
        assertThat(failureUsage.getSourceRef()).startsWith("context-build:" + failureTask.getTaskId() + ":failure_analysis:");
        assertThat(caseUsage.getConsumer()).isEqualTo(MemoryUsageConsumer.TEST_CASE_GENERATION);
        assertThat(caseUsage.getSourceRef()).startsWith("context-build:" + caseTask.getTaskId() + ":case_generation:");
    }

    @Test
    void usageRecordPreservesLowConfidenceMarkerFromRecalledMemory() {
        var task = tasks.save(newTask("direct-low-confidence-api"));
        var apiSpec = newApiSpec("/api/low-confidence", HttpMethod.GET);
        apiSpec.setSystemName(null);
        apiSpec.setModuleName(null);
        apiSpec.setPath(null);
        var refined = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Obscure naming reminder",
            "General naming note with little overlap to the next query.",
            MemorySourceType.MANUAL,
            "ltm-usage-low-confidence",
            task.getTaskId(),
            List.of(),
            0.56f,
            "Low confidence manual note.",
            Map.of()
        ));

        var bundle = unifiedContextBuilder.build(new UnifiedContextQuery(
            task.getTaskId(),
            null,
            null,
            apiSpec,
            "general",
            "zirconium alkali unrelated query",
            null,
            null,
            null,
            null,
            List.of(),
            400,
            MemoryUsageConsumer.CONTEXT_BUILDER,
            "context-builder:low-confidence"
        ));

        assertThat(bundle.longTermMemoryContext().hits()).hasSize(1);
        assertThat(bundle.longTermMemoryContext().hits().getFirst().memoryId()).isEqualTo(refined.memory().memoryId());
        assertThat(bundle.longTermMemoryContext().hits().getFirst().lowConfidence()).isTrue();
        var usage = usageRecords.findAllByTaskIdOrderByCreatedAtAscUsageIdAsc(task.getTaskId()).getFirst();
        assertThat(usage.getLowConfidence()).isTrue();
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
