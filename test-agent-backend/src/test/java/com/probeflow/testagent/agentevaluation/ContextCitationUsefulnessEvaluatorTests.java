package com.probeflow.testagent.agentevaluation;

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
import com.probeflow.testagent.memory.MemoryCandidateRequest;
import com.probeflow.testagent.memory.MemoryRefineryService;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.memory.MemoryUsageConsumer;
import com.probeflow.testagent.memory.MemoryUsageRecordRepository;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.testcasedraft.PromotionMode;
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
class ContextCitationUsefulnessEvaluatorTests {

    @Autowired
    private ContextCitationUsefulnessEvaluator evaluator;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private KnowledgeIngestApplicationService knowledgeIngest;

    @Autowired
    private MemoryRefineryService memoryRefineryService;

    @Autowired
    private MemoryUsageRecordRepository usageRecords;

    @Autowired
    private EntityManager entityManager;

    @Test
    void applicationServiceRunsSeededContextCitationDatasetThroughUnifiedContextBuilder() {
        seedRegistryContextFixture();
        var service = new AgentEvaluationApplicationService(
            new EvaluationDatasetRegistry(),
            List.of(evaluator)
        );

        var result = service.run(new EvaluationRunRequest(
            EvaluationDatasetRegistry.CONTEXT_CITATION_DATASET,
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE
        ));

        assertThat(result.run().status())
            .as(result.report().humanReadableSummary()
                + " fixes=" + result.report().recommendedFixes()
                + " metrics=" + result.report().metricSummary())
            .isEqualTo(EvaluationRunStatus.PASSED);
        assertThat(result.report().metricSummary()).containsKey(ContextCitationUsefulnessEvaluator.METRIC_NAME);
        assertThat(result.report().caseSummary().getFirst())
            .containsEntry("fixtureId", "context-citation-payment-auth")
            .containsEntry("status", EvaluationCaseStatus.PASSED.name());
        assertThat(usageRecords.findAllByTaskIdOrderByCreatedAtAscUsageIdAsc("phase8-context-task-payment"))
            .singleElement()
            .satisfies(record -> {
                assertThat(record.getConsumer()).isEqualTo(MemoryUsageConsumer.AGENT_EVALUATION);
                assertThat(record.getSourceRef()).contains("context-citation-payment-auth:eval-");
                assertThat(record.getCitationSourceRef()).isEqualTo("phase8-ltm-payment-auth");
            });
    }

    @Test
    void reportsIrrelevantCitationBudgetAndMissingCitationDiagnostics() {
        var seed = seedContext("phase8-diagnostic", true);
        var result = evaluator.evaluate(
            dataset(0.8d),
            fixture(
                "context-citation-diagnostics",
                seed,
                Map.of(
                    "expectedKnowledgeCitations", List.of("missing/wiki/payment-auth-note.md"),
                    "expectedMemoryCitations", List.of("missing-ltm-payment-auth"),
                    "expectedCoverage", List.of("api", "task-state", "knowledge", "memory"),
                    "allowedIrrelevantCitationCount", 0,
                    "tokenBudget", 1,
                    "expectedLowConfidence", false
                ),
                Map.of("tokenBudget", 500)
            ),
            context()
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.FAILED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> {
                assertThat(metric.passed()).isFalse();
                assertThat(metric.diagnosticMessage())
                    .contains("missing knowledge citation")
                    .contains("missing memory citation")
                    .contains("irrelevant citation count")
                    .contains("budget mismatch")
                    .contains("budget overflow");
            });
    }

    @Test
    void passesLowConfidenceFixtureWhenKnowledgeAndMemoryAreAbsent() {
        var apiSpec = apiSpecs.save(newApiSpec("phase8-low-confidence-api", "/api/orders/{orderId}/pay"));
        var task = tasks.save(newTask("phase8-low-confidence-task", apiSpec.getApiSpecId()));

        var result = evaluator.evaluate(
            dataset(0.8d),
            fixture(
                "context-citation-low-confidence",
                new Seed(apiSpec.getApiSpecId(), task.getTaskId()),
                Map.of(
                    "expectedCoverage", List.of("api", "task-state"),
                    "allowedIrrelevantCitationCount", 0,
                    "tokenBudget", 300,
                    "expectedLowConfidence", true
                ),
                Map.of("rawQuery", "no matching knowledge or memory", "tags", List.of("absent"), "tokenBudget", 300)
            ),
            context()
        );

        assertThat(result.status()).isEqualTo(EvaluationCaseStatus.PASSED);
        assertThat(result.metricResults()).singleElement()
            .satisfies(metric -> assertThat(metric.actual()).containsEntry("lowConfidence", true));
    }

    private void seedRegistryContextFixture() {
        var apiSpec = apiSpecs.save(newApiSpec("phase8-context-api-payment", "/api/orders/{orderId}/pay"));
        tasks.save(newTask("phase8-context-task-payment", apiSpec.getApiSpecId()));
        ingestKnowledge("Payment auth note", "phase8/wiki/payment-auth-note.md", "PAY_401 requires tenant bootstrap.");
        var archived = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Archived PAY_401 tenant bootstrap memory",
            "Archived memory should not be recalled by evaluation.",
            MemorySourceType.OBSERVATION,
            "phase8-ltm-archived-payment-auth",
            "phase8-context-task-payment",
            List.of("payment", "auth", "tenant", "pay_401"),
            0.91f,
            "Superseded execution note.",
            Map.of(
                "systemName", "order-platform",
                "module", "payment",
                "apiPath", "/api/orders/{orderId}/pay",
                "errorCode", "PAY_401"
            )
        ));
        memoryRefineryService.archiveMemory(archived.memory().memoryId());
        var refined = memoryRefineryService.refine(new MemoryCandidateRequest(
            "PAY_401 tenant bootstrap memory",
            "Failure analysis learned that PAY_401 on payment execution usually means tenant bootstrap was skipped before auth.",
            MemorySourceType.EXECUTION_RESULT,
            "phase8-ltm-payment-auth",
            "phase8-context-task-payment",
            List.of("payment", "auth", "tenant", "PAY_401"),
            0.92f,
            "ExecutionRecord showed PAY_401 disappears after tenant bootstrap is restored.",
            Map.of(
                "systemName", "order-platform",
                "module", "payment",
                "apiPath", "/api/orders/{orderId}/pay",
                "errorCode", "PAY_401"
            )
        ));
        assertThat(refined.accepted()).isTrue();
        entityManager.flush();
        entityManager.clear();
    }

    private Seed seedContext(String idPrefix, boolean includeIrrelevant) {
        var apiSpec = apiSpecs.save(newApiSpec(idPrefix + "-api", "/api/orders/{orderId}/pay"));
        var task = tasks.save(newTask(idPrefix + "-task", apiSpec.getApiSpecId()));
        ingestKnowledge(
            idPrefix + " payment auth note",
            idPrefix + "/wiki/payment-auth-note.md",
            "PAY_401 payment auth requires tenant bootstrap before signature validation."
        );
        var refined = memoryRefineryService.refine(new MemoryCandidateRequest(
            idPrefix + " PAY_401 tenant bootstrap memory",
            "Failure analysis learned that PAY_401 on payment execution usually means tenant bootstrap was skipped before auth.",
            MemorySourceType.EXECUTION_RESULT,
            idPrefix + "-ltm-payment-auth",
            task.getTaskId(),
            List.of("payment", "auth", "tenant", "PAY_401"),
            0.92f,
            "ExecutionRecord showed PAY_401 disappears after tenant bootstrap is restored.",
            Map.of(
                "systemName", "order-platform",
                "module", "payment",
                "apiPath", "/api/orders/{orderId}/pay",
                "errorCode", "PAY_401"
            )
        ));
        assertThat(refined.accepted()).isTrue();
        if (includeIrrelevant) {
            ingestKnowledge(
                idPrefix + " extra payment auth note",
                idPrefix + "/wiki/payment-auth-extra.md",
                "PAY_401 tenant bootstrap also mentions an extra unrelated retry dashboard note."
            );
            memoryRefineryService.refine(new MemoryCandidateRequest(
                idPrefix + " unrelated PAY_401 dashboard memory",
                "PAY_401 dashboard note is unrelated to the tenant bootstrap recovery path.",
                MemorySourceType.MANUAL,
                idPrefix + "-ltm-unrelated-dashboard",
                task.getTaskId(),
                List.of("payment", "auth", "tenant"),
                0.88f,
                "Unrelated manual note.",
                Map.of(
                    "systemName", "order-platform",
                    "module", "payment",
                    "apiPath", "/api/orders/{orderId}/pay",
                    "errorCode", "PAY_401"
                )
            ));
        }
        entityManager.flush();
        entityManager.clear();
        return new Seed(apiSpec.getApiSpecId(), task.getTaskId());
    }

    private GoldenTaskFixture fixture(
        String fixtureId,
        Seed seed,
        Map<String, Object> expected,
        Map<String, Object> setupOverrides
    ) {
        var setup = new java.util.LinkedHashMap<String, Object>();
        setup.put("taskId", seed.taskId());
        setup.put("apiSpecId", seed.apiSpecId());
        setup.put("stageProfile", "failure_analysis");
        setup.put("rawQuery", "payment auth PAY_401 tenant bootstrap");
        setup.put("errorCode", "PAY_401");
        setup.put("tags", List.of("payment", "auth", "tenant", "PAY_401"));
        setup.put("tokenBudget", 500);
        setup.putAll(setupOverrides);
        return new GoldenTaskFixture(
            fixtureId,
            List.of("context-citation"),
            "Evaluate context citation fixture.",
            EvaluationFixtureType.CONTEXT_CITATION,
            expected,
            setup
        );
    }

    private EvaluationRunContext context() {
        return new EvaluationRunContext(
            "eval-context",
            "ci-deterministic",
            EvaluationProviderMode.DETERMINISTIC_FAKE,
            "fixture-eval-context"
        );
    }

    private EvaluationDataset dataset(double threshold) {
        return new EvaluationDataset(
            "context-citation-test",
            "2026-07-07",
            List.of(),
            threshold,
            Map.of(ContextCitationUsefulnessEvaluator.METRIC_NAME, threshold),
            Map.of(ContextCitationUsefulnessEvaluator.METRIC_NAME, 2.0d),
            Map.of()
        );
    }

    private void ingestKnowledge(String title, String sourceRef, String body) {
        knowledgeIngest.ingest(new KnowledgeIngestRequest(
            title,
            KnowledgeContentFormat.MARKDOWN,
            "# " + title + "\n\nPOST /api/orders/{orderId}/pay\n\n" + body,
            DocumentSourceType.WIKI,
            sourceRef,
            DocumentType.API_NOTE,
            DocumentAuthority.HIGH,
            "order-platform",
            "payment",
            "order",
            List.of("payment", "auth", "tenant", "pay_401"),
            List.of("failure_analysis"),
            Map.of()
        ));
    }

    private Task newTask(String taskId, String apiSpecId) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Payment auth failure investigation");
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("phase8-context-citation");
        task.setTargetApiSpecIds(List.of(apiSpecId));
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.PENDING);
        task.setPriority(TaskPriority.HIGH);
        task.setCreator("phase8-agent-evaluation");
        task.setMetadata(Map.of("goal", "evaluate context citation quality"));
        return task;
    }

    private ApiSpec newApiSpec(String apiSpecId, String path) {
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId(apiSpecId);
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("payment");
        apiSpec.setHttpMethod(HttpMethod.POST);
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

    private record Seed(String apiSpecId, String taskId) {
    }
}
