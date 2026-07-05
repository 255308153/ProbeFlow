package com.probeflow.testagent.httpexecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.ExecutorType;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
import com.probeflow.testagent.taskcaseexecution.ExecutionMode;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionRepository;
import com.probeflow.testagent.taskcaseexecution.TaskCaseExecutionStatus;
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
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HttpExecutionApplicationServiceTests {

    @Autowired
    private HttpExecutionApplicationService executionService;

    @Autowired
    private FakeHttpClientGateway fakeHttpClient;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TestCaseRepository testCases;

    @Autowired
    private ExecutionRecordRepository executionRecords;

    @Autowired
    private TaskCaseExecutionRepository taskCaseExecutions;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void resetFakeHttpClient() {
        fakeHttpClient.reset();
    }

    @Test
    void singleExecutionThroughFakeHttpClientPersistsRecordAndTaskCaseExecution() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId()));
        fakeHttpClient.respondWith(new HttpClientResponse(
            201,
            Map.of("Content-Type", "application/json"),
            Map.of("orderId", "order-123", "status", "CREATED"),
            42L
        ));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.taskId()).isEqualTo(task.getTaskId());
        assertThat(result.environment()).isEqualTo("test");
        assertThat(result.caseResults()).singleElement()
            .satisfies(caseResult -> {
                assertThat(caseResult.caseId()).isEqualTo(testCase.getCaseId());
                assertThat(caseResult.status()).isEqualTo(HttpExecutionOutcomeStatus.PASSED);
                assertThat(caseResult.executionRecordId()).isNotBlank();
                assertThat(caseResult.durationMs()).isEqualTo(42L);
                assertThat(caseResult.statusCode()).isEqualTo(201);
            });
        assertThat(result.counts()).isEqualTo(new HttpExecutionCounts(1, 1, 0, 0, 0, 0));

        var recordId = result.caseResults().getFirst().executionRecordId();
        var record = executionRecords.findById(recordId).orElseThrow();
        assertThat(record.getTaskId()).isEqualTo(task.getTaskId());
        assertThat(record.getCaseId()).isEqualTo(testCase.getCaseId());
        assertThat(record.getExecutorType()).isEqualTo(ExecutorType.HTTP);
        assertThat(record.getEnvironment()).isEqualTo("test");
        assertThat(record.getRequestSnapshot()).containsEntry("method", "POST");
        assertThat(record.getRequestSnapshot()).containsEntry("path", "/api/orders");
        assertThat(record.getResponseSnapshot()).containsEntry("statusCode", 201);
        assertThat(record.getResponseSnapshot()).containsEntry("body", Map.of("orderId", "order-123", "status", "CREATED"));
        assertThat(record.getDurationMs()).isEqualTo(42L);
        assertThat(record.getStatusCode()).isEqualTo(201);
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.PASSED);

        var taskCaseExecution = taskCaseExecutions.findFirstByTaskIdAndCaseId(task.getTaskId(), testCase.getCaseId())
            .orElseThrow();
        assertThat(taskCaseExecution.getExecutionMode()).isEqualTo(ExecutionMode.SINGLE);
        assertThat(taskCaseExecution.getExecutionStatus()).isEqualTo(TaskCaseExecutionStatus.COMPLETED);
        assertThat(taskCaseExecution.getExecutionRecordId()).isEqualTo(recordId);
        assertThat(taskCaseExecution.getSnapshotJson()).containsEntry("environment", "test");

        assertThat(fakeHttpClient.requests()).singleElement()
            .satisfies(request -> {
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.path()).isEqualTo("/api/orders");
                assertThat(request.body()).isEqualTo(Map.of("skuId", "A-100", "quantity", 2));
            });
    }

    @Test
    void missingTaskReferenceIsRejectedClearly() {
        assertThatThrownBy(() -> executionService.execute(new HttpExecutionRequest(
            "missing-task",
            List.of("case-01"),
            ExecutionMode.SINGLE,
            "test",
            false,
            HttpExecutionOptions.defaults()
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Task not found: missing-task");
    }

    @Test
    void dryRunResolvesEnvironmentAndAuthPlaceholdersRedactsSecretsAndSkipsTransport() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), Map.of(
            "method", "POST",
            "path", "/api/orders/{{orderId}}",
            "headers", Map.of(
                "Authorization", "Bearer {{authToken}}",
                "X-Tenant", "{{tenantId}}"
            ),
            "body", Map.of(
                "skuId", "{{skuId}}",
                "password", "{{apiPassword}}"
            )
        )));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "staging",
            true,
            HttpExecutionOptions.defaults(),
            Map.of(
                "baseUrl", "https://api.staging.example",
                "orderId", "order-123",
                "tenantId", "tenant-a",
                "skuId", "A-100",
                "apiPassword", "secret-password"
            ),
            Map.of("authToken", "token-abc")
        ));

        assertThat(result.caseResults()).singleElement()
            .satisfies(caseResult -> {
                assertThat(caseResult.status()).isEqualTo(HttpExecutionOutcomeStatus.SKIPPED);
                assertThat(caseResult.executionRecordId()).isNull();
                assertThat(caseResult.message()).contains("Dry run prepared request");
                assertThat(caseResult.requestSnapshot()).containsEntry("url", "https://api.staging.example/api/orders/order-123");
                assertThat(caseResult.requestSnapshot()).containsEntry("path", "/api/orders/order-123");

                @SuppressWarnings("unchecked")
                var headers = (Map<String, Object>) caseResult.requestSnapshot().get("headers");
                assertThat(headers).containsEntry("Authorization", "[REDACTED]");
                assertThat(headers).containsEntry("X-Tenant", "tenant-a");

                @SuppressWarnings("unchecked")
                var body = (Map<String, Object>) caseResult.requestSnapshot().get("body");
                assertThat(body).containsEntry("skuId", "A-100");
                assertThat(body).containsEntry("password", "[REDACTED]");
            });
        assertThat(result.counts()).isEqualTo(new HttpExecutionCounts(1, 0, 0, 0, 1, 0));
        assertThat(fakeHttpClient.requests()).isEmpty();
        assertThat(executionRecords.findAll()).isEmpty();
    }

    @Test
    void executionUsesApiSpecFallbackRouteAndAuthMetadataWhenRequestShapeOmitsThem() {
        var apiSpec = newApiSpec();
        apiSpec.setHttpMethod(HttpMethod.GET);
        apiSpec.setPath("/api/orders/status");
        apiSpec.setAuth(Map.of(
            "type", "bearer",
            "header", "Authorization",
            "tokenVariable", "apiToken"
        ));
        apiSpec = apiSpecs.save(apiSpec);
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), Map.of(
            "body", Map.of("ping", true)
        )));
        fakeHttpClient.respondWith(new HttpClientResponse(
            200,
            Map.of("Content-Type", "application/json"),
            Map.of("healthy", true),
            12L
        ));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "dev",
            false,
            HttpExecutionOptions.defaults(),
            Map.of("baseUrl", "https://api.dev.example"),
            Map.of("apiToken", "token-xyz")
        ));

        assertThat(result.caseResults()).singleElement()
            .satisfies(caseResult -> {
                assertThat(caseResult.status()).isEqualTo(HttpExecutionOutcomeStatus.PASSED);
                assertThat(caseResult.requestSnapshot()).containsEntry("method", "GET");
                assertThat(caseResult.requestSnapshot()).containsEntry("url", "https://api.dev.example/api/orders/status");
            });
        assertThat(fakeHttpClient.requests()).singleElement()
            .satisfies(request -> {
                assertThat(request.method()).isEqualTo("GET");
                assertThat(request.path()).isEqualTo("/api/orders/status");
                assertThat(request.url()).isEqualTo("https://api.dev.example/api/orders/status");
                assertThat(request.headers()).containsEntry("Authorization", "Bearer token-xyz");
            });

        var recordId = result.caseResults().getFirst().executionRecordId();
        var record = executionRecords.findById(recordId).orElseThrow();
        @SuppressWarnings("unchecked")
        var persistedHeaders = (Map<String, Object>) record.getRequestSnapshot().get("headers");
        assertThat(persistedHeaders).containsEntry("Authorization", "[REDACTED]");
    }

    @Test
    void unresolvedEnvironmentVariableBlocksBeforeTransport() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), Map.of(
            "method", "GET",
            "path", "/api/orders/{{orderId}}"
        )));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "dev",
            false,
            HttpExecutionOptions.defaults(),
            Map.of("baseUrl", "https://api.dev.example"),
            Map.of()
        ));

        assertThat(result.caseResults()).singleElement()
            .satisfies(caseResult -> {
                assertThat(caseResult.status()).isEqualTo(HttpExecutionOutcomeStatus.BLOCKED);
                assertThat(caseResult.executionRecordId()).isNull();
                assertThat(caseResult.message()).contains("Unresolved variable: orderId");
            });
        assertThat(result.counts()).isEqualTo(new HttpExecutionCounts(1, 0, 0, 0, 0, 1));
        assertThat(fakeHttpClient.requests()).isEmpty();
        assertThat(executionRecords.findAll()).isEmpty();
    }

    @Test
    void unsupportedProtocolBlocksBeforeTransport() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), Map.of(
            "method", "GET",
            "path", "/api/orders"
        )));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "dev",
            false,
            HttpExecutionOptions.defaults(),
            Map.of("baseUrl", "ftp://api.dev.example"),
            Map.of()
        ));

        assertThat(result.caseResults()).singleElement()
            .satisfies(caseResult -> {
                assertThat(caseResult.status()).isEqualTo(HttpExecutionOutcomeStatus.BLOCKED);
                assertThat(caseResult.message()).contains("Unsupported protocol: ftp");
            });
        assertThat(result.counts()).isEqualTo(new HttpExecutionCounts(1, 0, 0, 0, 0, 1));
        assertThat(fakeHttpClient.requests()).isEmpty();
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
        apiSpec.setParameters(Map.of("body", Map.of(
            "skuId", Map.of("type", "string", "required", true),
            "quantity", Map.of("type", "integer", "required", true)
        )));
        apiSpec.setConstraints(Map.of());
        apiSpec.setAuth(Map.of());
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("openapi://orders.yaml#/paths/~1api~1orders/post");
        apiSpec.setSourceLocation(Map.of("line", 42));
        apiSpec.setVersion(3);
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(false);
        apiSpec.setKnowledgeContextReady(false);
        return apiSpec;
    }

    private Task newTask(String apiSpecId) {
        var task = new Task();
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Execute order API cases");
        task.setStatus(TaskStatus.EXECUTING);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("phase6-issue-01");
        task.setTargetApiSpecIds(List.of(apiSpecId));
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.NOT_REQUIRED);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase6-test");
        task.setMetadata(Map.of("phase", "6", "issue", "01"));
        return task;
    }

    private TestCase newSingleTestCase(String apiSpecId) {
        return newSingleTestCase(apiSpecId, Map.of(
            "method", "POST",
            "path", "/api/orders",
            "headers", Map.of("Content-Type", "application/json"),
            "body", Map.of("skuId", "A-100", "quantity", 2)
        ));
    }

    private TestCase newSingleTestCase(String apiSpecId, Map<String, Object> requestShape) {
        var testCase = new TestCase();
        testCase.setPrimaryApiSpecId(apiSpecId);
        testCase.setCaseCategory(CaseCategory.API);
        testCase.setMode(TestCaseMode.SINGLE);
        testCase.setTitle("Create order happy path");
        testCase.setDescription("Create an order with a valid SKU and quantity.");
        testCase.setPreconditions(List.of("Order API is available"));
        testCase.setExpectedResult("HTTP 201 with created order body");
        testCase.setPriority(CasePriority.MEDIUM);
        testCase.setRiskLevel(CaseRiskLevel.MEDIUM);
        testCase.setTags(List.of("api", "single", "happy-path"));
        testCase.setScenarioName("happy-path");
        testCase.setModuleName("order");
        testCase.setStatus(CaseStatus.READY);
        testCase.setSource(CaseSource.STRUCTURE);
        testCase.setManualEdited(false);
        testCase.setLocked(false);
        testCase.setDetailType(DetailType.API);
        testCase.setDetail(Map.of(
            "expectedStatus", 201,
            "requestShape", requestShape
        ));
        testCase.setSteps(List.of(Map.of(
            "order", 1,
            "apiSpecId", apiSpecId,
            "expectedStatus", 201,
            "requestShape", requestShape
        )));
        testCase.setBasedOnApiSpecVersions(Map.of(apiSpecId, 3));
        return testCase;
    }

    @TestConfiguration
    static class FakeHttpClientConfiguration {

        @Bean
        @Primary
        FakeHttpClientGateway fakeHttpClientGateway() {
            return new FakeHttpClientGateway();
        }
    }
}
