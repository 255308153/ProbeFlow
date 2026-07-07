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
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.memory.TaskMemoryService;
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
    private TaskMemoryService taskMemoryService;

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

        var memories = taskMemoryService.readActiveTaskMemories(task.getTaskId(), "execution");
        assertThat(memories).singleElement()
            .satisfies(memory -> {
                assertThat(memory.sourceType()).isEqualTo(MemorySourceType.EXECUTION_RESULT);
                assertThat(memory.sourceRef()).isEqualTo(recordId);
                assertThat(memory.lifecycleStage()).isEqualTo("execution");
                assertThat(memory.tags()).contains("http-execution", "passed", "test");
                assertThat(memory.metadata())
                    .containsEntry("taskId", task.getTaskId())
                    .containsEntry("caseId", testCase.getCaseId())
                    .containsEntry("executionId", recordId)
                    .containsEntry("environment", "test")
                    .containsEntry("status", "PASSED")
                    .containsEntry("httpStatus", 201);
                assertThat(((Number) memory.metadata().get("durationMs")).longValue()).isEqualTo(42L);
            });

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
                assertThat(caseResult.executionRecordId()).isNotBlank();
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

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.SKIPPED);
        assertThat(record.getResponseSnapshot()).containsEntry("dryRun", true);
        assertThat(record.getResponseSnapshot()).containsEntry("durationMs", 0L);
        assertThat(taskMemoryService.readActiveTaskMemories(task.getTaskId(), "execution")).isEmpty();
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
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 200, Map.of(
            "body", Map.of("ping", true)
        ), List.of()));
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
                assertThat(caseResult.executionRecordId()).isNotBlank();
                assertThat(caseResult.message()).contains("Unresolved variable: orderId");
            });
        assertThat(result.counts()).isEqualTo(new HttpExecutionCounts(1, 0, 0, 0, 0, 1));
        assertThat(fakeHttpClient.requests()).isEmpty();

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.BLOCKED);
        assertThat(record.getErrorMessage()).contains("Unresolved variable: orderId");
        assertThat(record.getResponseSnapshot()).containsEntry("errorType", "BLOCKED_REQUEST");
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

    @Test
    void blockedHostPolicyBlocksBeforeTransportWithClassification() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 200, requestShape("/api/orders"), List.of()));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "dev",
            false,
            HttpExecutionOptions.defaults(),
            Map.of("baseUrl", "http://127.0.0.1:8080"),
            Map.of()
        ));

        assertThat(result.caseResults()).singleElement()
            .satisfies(caseResult -> {
                assertThat(caseResult.status()).isEqualTo(HttpExecutionOutcomeStatus.BLOCKED);
                assertThat(caseResult.message()).contains("Blocked host: 127.0.0.1");
            });
        assertThat(fakeHttpClient.requests()).isEmpty();

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.BLOCKED);
        assertThat(record.getResponseSnapshot()).containsEntry("errorType", "BLOCKED_HOST");
    }

    @Test
    void invalidUrlBlocksBeforeTransportWithInvalidRequestClassification() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 200, requestShape("/api/orders"), List.of()));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "dev",
            false,
            HttpExecutionOptions.defaults(),
            Map.of("baseUrl", "http://[bad-host"),
            Map.of()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.BLOCKED);
        assertThat(fakeHttpClient.requests()).isEmpty();

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getResponseSnapshot()).containsEntry("errorType", "INVALID_REQUEST");
        assertThat(record.getErrorMessage()).contains("Invalid URL");
    }

    @Test
    void requestSnapshotPreservesExplicitRedirectAndRetryPolicyMetadata() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId()));
        fakeHttpClient.respondWith(new HttpClientResponse(
            201,
            Map.of("Content-Type", "application/json"),
            Map.of("orderId", "order-123"),
            17L
        ));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "test",
            false,
            new HttpExecutionOptions(30_000L, true, false, HttpRedirectPolicy.NEVER, 0, List.of())
        ));

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var executionPolicy = (Map<String, Object>) record.getRequestSnapshot().get("executionPolicy");
        assertThat(executionPolicy)
            .containsEntry("redirectPolicy", "NEVER")
            .containsEntry("maxRetries", 0)
            .containsEntry("timeoutMs", 30_000L);
        assertThat(fakeHttpClient.requests()).hasSize(1);
    }

    @Test
    void successfulResponseSnapshotCapturesHeadersBodyMetadataAndTruncatesLargeText() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 200, defaultRequestShape(), List.of()));
        var largeBody = "0123456789".repeat(200);
        fakeHttpClient.respondWith(new HttpClientResponse(
            200,
            Map.of("Content-Type", "text/plain", "X-Trace-Id", "trace-001"),
            largeBody,
            64L
        ));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.PASSED);
        assertThat(record.getStatusCode()).isEqualTo(200);
        assertThat(record.getDurationMs()).isEqualTo(64L);
        assertThat(record.getResponseSnapshot()).containsEntry("statusCode", 200);
        assertThat(record.getResponseSnapshot()).containsEntry("bodyType", "text");
        assertThat(record.getResponseSnapshot()).containsEntry("bodyTruncated", true);
        assertThat(record.getResponseSnapshot().get("bodyExcerpt")).asString().startsWith("0123456789");
        assertThat(record.getResponseSnapshot().get("bodyExcerpt")).asString().hasSizeLessThan(largeBody.length());
        assertThat(record.getResponseSnapshot()).containsEntry("bodySizeBytes", largeBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        @SuppressWarnings("unchecked")
        var headers = (Map<String, Object>) record.getResponseSnapshot().get("headers");
        assertThat(headers).containsEntry("Content-Type", "text/plain");
        assertThat(headers).containsEntry("X-Trace-Id", "trace-001");
    }

    @Test
    void binaryResponseSnapshotStoresMetadataWithoutRawBytes() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 200, defaultRequestShape(), List.of()));
        fakeHttpClient.respondWith(new HttpClientResponse(
            200,
            Map.of("Content-Type", "application/octet-stream"),
            new byte[] {1, 2, 3, 4},
            19L
        ));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getResponseSnapshot()).containsEntry("bodyType", "binary");
        assertThat(record.getResponseSnapshot()).containsEntry("bodySizeBytes", 4);
        assertThat(record.getResponseSnapshot()).containsEntry("bodyTruncated", false);
        assertThat(record.getResponseSnapshot()).doesNotContainKey("body");
        assertThat(record.getResponseSnapshot()).doesNotContainKey("bodyExcerpt");
    }

    @Test
    void networkErrorPersistsExecutionRecordWithErrorSnapshot() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId()));
        fakeHttpClient.failWith(HttpTransportException.networkError("Connection refused", 33L));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults()).singleElement()
            .satisfies(caseResult -> {
                assertThat(caseResult.status()).isEqualTo(HttpExecutionOutcomeStatus.ERROR);
                assertThat(caseResult.executionRecordId()).isNotBlank();
                assertThat(caseResult.message()).contains("Connection refused");
            });
        assertThat(result.counts()).isEqualTo(new HttpExecutionCounts(1, 0, 0, 1, 0, 0));

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.ERROR);
        assertThat(record.getDurationMs()).isEqualTo(33L);
        assertThat(record.getErrorMessage()).contains("Connection refused");
        assertThat(record.getResponseSnapshot()).containsEntry("errorType", "NETWORK_ERROR");
        assertThat(record.getResponseSnapshot()).containsEntry("durationMs", 33L);

        var memories = taskMemoryService.readActiveTaskMemories(task.getTaskId(), "execution");
        assertThat(memories).singleElement()
            .satisfies(memory -> {
                assertThat(memory.scopeType()).isEqualTo(MemoryScopeType.FAILURE_PATTERN);
                assertThat(memory.sourceType()).isEqualTo(MemorySourceType.EXECUTION_RESULT);
                assertThat(memory.sourceRef()).isEqualTo(record.getExecutionId());
                assertThat(memory.summary()).contains("HTTP execution ERROR");
                assertThat(memory.content()).contains("Connection refused");
                assertThat(memory.metadata())
                    .containsEntry("caseId", testCase.getCaseId())
                    .containsEntry("executionId", record.getExecutionId())
                    .containsEntry("environment", "test")
                    .containsEntry("status", "ERROR")
                    .containsEntry("errorSummary", "Connection refused")
                    .containsEntry("errorType", "NETWORK_ERROR");
            });
    }

    @Test
    void timeoutPersistsExecutionRecordWithTimeoutSnapshot() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId()));
        fakeHttpClient.failWith(HttpTransportException.timeout("Request timed out", 30_000L));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.ERROR);
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.ERROR);
        assertThat(record.getDurationMs()).isEqualTo(30_000L);
        assertThat(record.getResponseSnapshot()).containsEntry("errorType", "TIMEOUT");
        assertThat(record.getErrorMessage()).contains("Request timed out");
    }

    @Test
    void passingBaselineAssertionsArePersistedStructurally() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 201, defaultRequestShape(), List.of(
            Map.of("name", "response body present", "type", "BODY_PRESENT", "expected", true),
            Map.of("name", "order id exists", "type", "JSON_FIELD_EXISTS", "path", "$.orderId"),
            Map.of("name", "status equals created", "type", "JSON_FIELD_EQUALS", "path", "$.status", "expected", "CREATED"),
            Map.of("name", "fast enough", "type", "DURATION_LESS_THAN_MS", "expected", 100)
        )));
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

        assertThat(result.caseResults()).singleElement()
            .satisfies(caseResult -> assertThat(caseResult.status()).isEqualTo(HttpExecutionOutcomeStatus.PASSED));

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.PASSED);
        assertThat(record.isCriticalFailed()).isFalse();
        assertThat(record.getAssertionResults()).hasSize(5);
        assertThat(record.getAssertionResults()).allSatisfy(assertion -> assertThat(assertion)
            .containsEntry("status", "PASSED")
            .containsEntry("critical", true));
        assertThat(record.getAssertionResults()).anySatisfy(assertion -> assertThat(assertion)
            .containsEntry("type", "STATUS_CODE")
            .containsEntry("expected", 201)
            .containsEntry("actual", 201));
        assertThat(record.getAssertionResults()).anySatisfy(assertion -> assertThat(assertion)
            .containsEntry("type", "JSON_FIELD_EQUALS")
            .containsEntry("expected", "CREATED")
            .containsEntry("actual", "CREATED"));
    }

    @Test
    void failedExpectedStatusAssertionFailsExecutionRecord() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId()));
        fakeHttpClient.respondWith(new HttpClientResponse(
            500,
            Map.of("Content-Type", "application/json"),
            Map.of("error", "internal"),
            18L
        ));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.FAILED);
        assertThat(result.counts()).isEqualTo(new HttpExecutionCounts(1, 0, 1, 0, 0, 0));

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.FAILED);
        assertThat(record.isCriticalFailed()).isTrue();
        assertThat(record.getErrorMessage()).isNull();
        assertThat(record.getResponseSnapshot()).containsEntry("failureType", "ASSERTION_FAILURE");
        assertThat(record.getAssertionResults()).anySatisfy(assertion -> assertThat(assertion)
            .containsEntry("type", "STATUS_CODE")
            .containsEntry("expected", 201)
            .containsEntry("actual", 500)
            .containsEntry("status", "FAILED"));
    }

    @Test
    void failedExecutionWritesTaskMemoryAndLeavesTestCaseDefinitionUntouched() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = newSingleTestCase(apiSpec.getApiSpecId());
        testCase.setManualEdited(true);
        testCase.setLocked(true);
        testCase.setExpectedResult("HTTP 201 with manually reviewed order payload");
        var originalDetail = new java.util.LinkedHashMap<>(testCase.getDetail());
        var originalSteps = List.copyOf(testCase.getSteps());
        var originalExpectedResult = testCase.getExpectedResult();
        var savedCase = testCases.save(testCase);
        fakeHttpClient.respondWith(new HttpClientResponse(
            500,
            Map.of("Content-Type", "application/json"),
            Map.of("error", "internal"),
            18L
        ));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(savedCase.getCaseId()),
            ExecutionMode.SINGLE,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        var taskCaseExecution = taskCaseExecutions.findFirstByTaskIdAndCaseId(task.getTaskId(), savedCase.getCaseId())
            .orElseThrow();
        assertThat(taskCaseExecution.getExecutionStatus()).isEqualTo(TaskCaseExecutionStatus.FAILED);
        assertThat(taskCaseExecution.getExecutionRecordId()).isEqualTo(record.getExecutionId());

        var memories = taskMemoryService.readActiveTaskMemories(task.getTaskId(), "execution");
        assertThat(memories).singleElement()
            .satisfies(memory -> {
                assertThat(memory.scopeType()).isEqualTo(MemoryScopeType.FAILURE_PATTERN);
                assertThat(memory.sourceType()).isEqualTo(MemorySourceType.EXECUTION_RESULT);
                assertThat(memory.sourceRef()).isEqualTo(record.getExecutionId());
                assertThat(memory.summary()).contains("HTTP execution FAILED");
                assertThat(memory.metadata())
                    .containsEntry("taskId", task.getTaskId())
                    .containsEntry("caseId", savedCase.getCaseId())
                    .containsEntry("executionId", record.getExecutionId())
                    .containsEntry("environment", "test")
                    .containsEntry("status", "FAILED")
                    .containsEntry("httpStatus", 500);

                @SuppressWarnings("unchecked")
                var failedAssertions = (List<Map<String, Object>>) memory.metadata().get("failedAssertions");
                assertThat(failedAssertions).singleElement()
                    .satisfies(assertion -> assertThat(assertion)
                        .containsEntry("type", "STATUS_CODE")
                        .containsEntry("expected", 201)
                        .containsEntry("actual", 500)
                        .containsEntry("status", "FAILED"));
            });

        entityManager.flush();
        entityManager.clear();
        var reloadedCase = testCases.findById(savedCase.getCaseId()).orElseThrow();
        assertThat(reloadedCase.isManualEdited()).isTrue();
        assertThat(reloadedCase.isLocked()).isTrue();
        assertThat(reloadedCase.getExpectedResult()).isEqualTo(originalExpectedResult);
        assertThat(reloadedCase.getDetail()).isEqualTo(originalDetail);
        assertThat(reloadedCase.getSteps()).isEqualTo(originalSteps);
    }

    @Test
    void bodyPresenceAndDurationAssertionsCanFail() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 204, defaultRequestShape(), List.of(
            Map.of("type", "BODY_PRESENT", "expected", true),
            Map.of("type", "DURATION_LESS_THAN_MS", "expected", 10)
        )));
        fakeHttpClient.respondWith(new HttpClientResponse(
            204,
            Map.of(),
            null,
            25L
        ));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.FAILED);

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getAssertionResults()).anySatisfy(assertion -> assertThat(assertion)
            .containsEntry("type", "BODY_PRESENT")
            .containsEntry("expected", true)
            .containsEntry("actual", false)
            .containsEntry("status", "FAILED"));
        assertThat(record.getAssertionResults()).anySatisfy(assertion -> assertThat(assertion)
            .containsEntry("type", "DURATION_LESS_THAN_MS")
            .containsEntry("expected", 10L)
            .containsEntry("actual", 25L)
            .containsEntry("status", "FAILED"));
    }

    @Test
    void nonCriticalAssertionFailurePersistsWarningOverallStatus() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 200, defaultRequestShape(), List.of(
            Map.of(
                "type", "JSON_FIELD_EQUALS",
                "path", "$.status",
                "expected", "CREATED",
                "critical", false
            )
        )));
        fakeHttpClient.respondWith(new HttpClientResponse(
            200,
            Map.of("Content-Type", "application/json"),
            Map.of("status", "PENDING"),
            7L
        ));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.PASSED);

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.PASSED_WITH_WARNINGS);
        assertThat(record.isCriticalFailed()).isFalse();
        assertThat(record.getAssertionResults()).anySatisfy(assertion -> assertThat(assertion)
            .containsEntry("type", "JSON_FIELD_EQUALS")
            .containsEntry("status", "FAILED")
            .containsEntry("critical", false));
    }

    @Test
    void malformedJsonBodyFailsJsonFieldAssertionWithoutThrowing() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var testCase = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 200, defaultRequestShape(), List.of(
            Map.of("type", "JSON_FIELD_EXISTS", "path", "$.orderId")
        )));
        fakeHttpClient.respondWith(new HttpClientResponse(
            200,
            Map.of("Content-Type", "application/json"),
            "{not-json",
            5L
        ));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(testCase.getCaseId()),
            ExecutionMode.SINGLE,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.FAILED);

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.FAILED);
        assertThat(record.getAssertionResults()).anySatisfy(assertion -> assertThat(assertion)
            .containsEntry("type", "JSON_FIELD_EXISTS")
            .containsEntry("status", "FAILED")
            .containsEntry("actual", false));
        assertThat(record.getAssertionResults()).anySatisfy(assertion -> assertThat(assertion.get("message")).asString()
            .contains("valid JSON"));
    }

    @Test
    void batchExecutesSelectedCasesInDeterministicOrderAndAggregatesCounts() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var first = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 201, requestShape("/api/orders/first"), List.of()));
        var second = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 201, requestShape("/api/orders/second"), List.of()));
        var third = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 200, requestShape("/api/orders/third"), List.of()));
        fakeHttpClient.respondWithSequence(
            new HttpClientResponse(201, Map.of(), Map.of("id", "first"), 10L),
            new HttpClientResponse(500, Map.of(), Map.of("error", "boom"), 11L),
            new HttpClientResponse(200, Map.of(), Map.of("id", "third"), 12L)
        );

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(first.getCaseId(), second.getCaseId(), third.getCaseId()),
            ExecutionMode.BATCH,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults()).extracting(HttpExecutionCaseResult::caseId)
            .containsExactly(first.getCaseId(), second.getCaseId(), third.getCaseId());
        assertThat(result.caseResults()).extracting(HttpExecutionCaseResult::status)
            .containsExactly(HttpExecutionOutcomeStatus.PASSED, HttpExecutionOutcomeStatus.FAILED, HttpExecutionOutcomeStatus.PASSED);
        assertThat(result.counts()).isEqualTo(new HttpExecutionCounts(3, 2, 1, 0, 0, 0));
        assertThat(fakeHttpClient.requests()).extracting(HttpClientRequest::path)
            .containsExactly("/api/orders/first", "/api/orders/second", "/api/orders/third");
        assertThat(result.caseResults()).allSatisfy(caseResult -> assertThat(caseResult.executionRecordId()).isNotBlank());
    }

    @Test
    void batchStopOnCriticalFailureSkipsLaterCasesWithReasons() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var first = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 201, requestShape("/api/orders/first"), List.of()));
        var second = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 201, requestShape("/api/orders/second"), List.of()));
        var third = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 201, requestShape("/api/orders/third"), List.of()));
        fakeHttpClient.respondWithSequence(new HttpClientResponse(500, Map.of(), Map.of("error", "boom"), 9L));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(first.getCaseId(), second.getCaseId(), third.getCaseId()),
            ExecutionMode.BATCH,
            "test",
            false,
            new HttpExecutionOptions(30_000L, true, true)
        ));

        assertThat(result.caseResults()).extracting(HttpExecutionCaseResult::status)
            .containsExactly(HttpExecutionOutcomeStatus.FAILED, HttpExecutionOutcomeStatus.SKIPPED, HttpExecutionOutcomeStatus.SKIPPED);
        assertThat(result.counts()).isEqualTo(new HttpExecutionCounts(3, 0, 1, 0, 2, 0));
        assertThat(fakeHttpClient.requests()).extracting(HttpClientRequest::path)
            .containsExactly("/api/orders/first");
        assertThat(result.caseResults().get(1).message()).contains("prior critical failure");
        assertThat(result.caseResults().get(2).message()).contains("prior critical failure");

        var skippedRecord = executionRecords.findById(result.caseResults().get(1).executionRecordId()).orElseThrow();
        assertThat(skippedRecord.getOverallStatus()).isEqualTo(OverallStatus.SKIPPED);
        assertThat(skippedRecord.getResponseSnapshot()).containsEntry("skipReason", "Skipped because a prior critical failure stopped the batch");
    }

    @Test
    void batchRepresentsMissingAndBlockedCasesWithoutHidingSuccessfulCases() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var success = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 201, requestShape("/api/orders/success"), List.of()));
        var blocked = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 201, requestShape("/api/orders/{{missingOrderId}}"), List.of()));
        fakeHttpClient.respondWithSequence(new HttpClientResponse(201, Map.of(), Map.of("id", "success"), 8L));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(success.getCaseId(), "missing-case", blocked.getCaseId()),
            ExecutionMode.BATCH,
            "test",
            false,
            HttpExecutionOptions.defaults(),
            Map.of(),
            Map.of()
        ));

        assertThat(result.caseResults()).extracting(HttpExecutionCaseResult::caseId)
            .containsExactly(success.getCaseId(), "missing-case", blocked.getCaseId());
        assertThat(result.caseResults()).extracting(HttpExecutionCaseResult::status)
            .containsExactly(HttpExecutionOutcomeStatus.PASSED, HttpExecutionOutcomeStatus.ERROR, HttpExecutionOutcomeStatus.BLOCKED);
        assertThat(result.counts()).isEqualTo(new HttpExecutionCounts(3, 1, 0, 1, 0, 1));
        assertThat(fakeHttpClient.requests()).extracting(HttpClientRequest::path)
            .containsExactly("/api/orders/success");
        assertThat(result.caseResults().get(1).message()).contains("TestCase not found");
        assertThat(result.caseResults().get(2).message()).contains("Unresolved variable");
    }

    @Test
    void batchContinuesAfterNonCriticalAssertionWarning() {
        var apiSpec = apiSpecs.save(newApiSpec());
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var warning = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 200, requestShape("/api/orders/warning"), List.of(
            Map.of("type", "JSON_FIELD_EQUALS", "path", "$.status", "expected", "CREATED", "critical", false)
        )));
        var success = testCases.save(newSingleTestCase(apiSpec.getApiSpecId(), 201, requestShape("/api/orders/success"), List.of()));
        fakeHttpClient.respondWithSequence(
            new HttpClientResponse(200, Map.of(), Map.of("status", "PENDING"), 6L),
            new HttpClientResponse(201, Map.of(), Map.of("id", "success"), 7L)
        );

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(warning.getCaseId(), success.getCaseId()),
            ExecutionMode.BATCH,
            "test",
            false,
            new HttpExecutionOptions(30_000L, true, true)
        ));

        assertThat(result.caseResults()).extracting(HttpExecutionCaseResult::status)
            .containsExactly(HttpExecutionOutcomeStatus.PASSED, HttpExecutionOutcomeStatus.PASSED);
        assertThat(result.counts()).isEqualTo(new HttpExecutionCounts(2, 2, 0, 0, 0, 0));
        assertThat(fakeHttpClient.requests()).extracting(HttpClientRequest::path)
            .containsExactly("/api/orders/warning", "/api/orders/success");

        var warningRecord = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(warningRecord.getOverallStatus()).isEqualTo(OverallStatus.PASSED_WITH_WARNINGS);
        assertThat(warningRecord.isCriticalFailed()).isFalse();
    }

    @Test
    void suiteExecutesOrderedStepsAndPersistsStepDetails() {
        var createOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders", 201));
        var readOrder = apiSpecs.save(newApiSpec(HttpMethod.GET, "/api/orders/order-123", 200));
        var task = tasks.save(newTask(List.of(createOrder.getApiSpecId(), readOrder.getApiSpecId())));
        var suite = testCases.save(newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStep(2, "read-order", readOrder.getApiSpecId(), "GET", "/api/orders/order-123", 200),
            suiteStep(1, "create-order", createOrder.getApiSpecId(), "POST", "/api/orders", 201)
        )));
        fakeHttpClient.respondWithSequence(
            new HttpClientResponse(201, Map.of(), Map.of("orderId", "order-123"), 21L),
            new HttpClientResponse(200, Map.of(), Map.of("orderId", "order-123", "status", "CREATED"), 13L)
        );

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults()).singleElement()
            .satisfies(caseResult -> {
                assertThat(caseResult.caseId()).isEqualTo(suite.getCaseId());
                assertThat(caseResult.status()).isEqualTo(HttpExecutionOutcomeStatus.PASSED);
                assertThat(caseResult.durationMs()).isEqualTo(34L);
                assertThat(caseResult.executionRecordId()).isNotBlank();
            });
        assertThat(result.counts()).isEqualTo(new HttpExecutionCounts(1, 1, 0, 0, 0, 0));
        assertThat(fakeHttpClient.requests()).extracting(HttpClientRequest::path)
            .containsExactly("/api/orders", "/api/orders/order-123");

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.PASSED);
        assertThat(record.getDurationMs()).isEqualTo(34L);
        assertThat(record.getRequestSnapshot()).containsEntry("suite", true);

        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) record.getResponseSnapshot().get("steps");
        assertThat(steps).hasSize(2);
        assertThat(steps).extracting(step -> step.get("stepId"))
            .containsExactly("create-order", "read-order");
        assertThat(steps).extracting(step -> step.get("status"))
            .containsExactly("PASSED", "PASSED");
        assertThat(steps.getFirst()).containsKeys("requestSnapshot", "responseSnapshot", "assertionResults");
        assertThat(record.getAssertionResults()).hasSize(2);

        var taskCaseExecution = taskCaseExecutions.findFirstByTaskIdAndCaseId(task.getTaskId(), suite.getCaseId())
            .orElseThrow();
        assertThat(taskCaseExecution.getExecutionRecordId()).isEqualTo(record.getExecutionId());
        assertThat(taskCaseExecution.getExecutionStatus()).isEqualTo(TaskCaseExecutionStatus.COMPLETED);
    }

    @Test
    void suiteExecutionContextExtractsOrderIdAndResolvesDownstreamRequests() {
        var createOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders", 201));
        var payOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders/order-123/pay", 200));
        var queryOrder = apiSpecs.save(newApiSpec(HttpMethod.GET, "/api/orders/order-123", 200));
        var task = tasks.save(newTask(List.of(createOrder.getApiSpecId(), payOrder.getApiSpecId(), queryOrder.getApiSpecId())));
        var suite = testCases.save(newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStepWithRuntime(
                1,
                "create-order",
                createOrder.getApiSpecId(),
                "POST",
                "/api/orders",
                201,
                List.of(Map.of(
                    "sourceType", "BODY_JSON",
                    "sourcePath", "$.data.orderId",
                    "targetScope", "SUITE",
                    "targetKey", "orderId",
                    "required", true,
                    "failureStrategy", "FAIL_FAST"
                ))
            ),
            suiteStepWithRuntime(2, "pay-order", payOrder.getApiSpecId(), "POST", "/api/orders/${suite.orderId}/pay", 200, List.of()),
            suiteStepWithRuntime(3, "query-order", queryOrder.getApiSpecId(), "GET", "/api/orders/${suite.orderId}", 200, List.of())
        )));
        fakeHttpClient.respondWithSequence(
            new HttpClientResponse(201, Map.of(), Map.of("data", Map.of("orderId", "order-123")), 21L),
            new HttpClientResponse(200, Map.of(), Map.of("paid", true), 11L),
            new HttpClientResponse(200, Map.of(), Map.of("orderId", "order-123", "status", "PAID"), 9L)
        );

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults(),
            Map.of("baseUrl", "https://api.example.test"),
            Map.of("authToken", "secret-token")
        ));

        assertThat(result.caseResults()).singleElement()
            .satisfies(caseResult -> assertThat(caseResult.status()).isEqualTo(HttpExecutionOutcomeStatus.PASSED));
        assertThat(fakeHttpClient.requests()).extracting(HttpClientRequest::path)
            .containsExactly("/api/orders", "/api/orders/order-123/pay", "/api/orders/order-123");
        assertThat(fakeHttpClient.requests()).extracting(HttpClientRequest::url)
            .containsExactly(
                "https://api.example.test/api/orders",
                "https://api.example.test/api/orders/order-123/pay",
                "https://api.example.test/api/orders/order-123"
            );

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.PASSED);
        assertThat(record.getRequestSnapshot()).containsEntry("suite", true);

        @SuppressWarnings("unchecked")
        var responseSnapshot = record.getResponseSnapshot();
        @SuppressWarnings("unchecked")
        var auditSummary = (Map<String, Object>) responseSnapshot.get("variableAuditSummary");
        assertThat(auditSummary).containsEntry("productionEvents", 1L);
        assertThat(auditSummary).containsEntry("consumptionEvents", 2L);

        @SuppressWarnings("unchecked")
        var events = (List<Map<String, Object>>) auditSummary.get("events");
        assertThat(events)
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "PRODUCTION")
                .containsEntry("stepId", "create-order")
                .containsEntry("targetScope", "suite")
                .containsEntry("targetKey", "orderId"))
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "CONSUMPTION")
                .containsEntry("stepId", "pay-order")
                .containsEntry("expression", "${suite.orderId}")
                .containsEntry("resolved", true))
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "CONSUMPTION")
                .containsEntry("stepId", "query-order")
                .containsEntry("expression", "${suite.orderId}")
                .containsEntry("resolved", true));

        @SuppressWarnings("unchecked")
        var contextSummary = (Map<String, Object>) responseSnapshot.get("contextSummary");
        assertThat(contextSummary.toString()).contains("order-123").doesNotContain("secret-token");
        assertThat(responseSnapshot).containsKey("runtimeDiagnostics");
    }

    @Test
    void variableResolverCoversScopesPathsLocationsAndTypePreservation() {
        var createOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders", 201));
        var payOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders/order-123/pay", 200));
        var task = newTask(List.of(createOrder.getApiSpecId(), payOrder.getApiSpecId()));
        task.setMetadata(Map.of("externalTaskId", "task-42"));
        task = tasks.save(task);
        var suiteCase = newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStepWithRuntime(
                1,
                "create-order",
                createOrder.getApiSpecId(),
                "POST",
                "/api/orders",
                201,
                List.of(
                    Map.of(
                        "sourceType", "BODY_JSON",
                        "sourcePath", "$.data.order",
                        "targetScope", "SUITE",
                        "targetKey", "order",
                        "required", true,
                        "failureStrategy", "FAIL_FAST"
                    ),
                    Map.of(
                        "sourceType", "BODY_JSON",
                        "sourcePath", "$.data.items",
                        "targetScope", "SUITE",
                        "targetKey", "items",
                        "required", true,
                        "failureStrategy", "FAIL_FAST"
                    ),
                    Map.of(
                        "sourceType", "BODY_JSON",
                        "sourcePath", "$.data.order.id",
                        "targetScope", "STEP",
                        "targetKey", "orderId",
                        "required", true,
                        "failureStrategy", "FAIL_FAST"
                    )
                )
            ),
            suiteStepWithTemplate(
                2,
                "pay-order",
                payOrder.getApiSpecId(),
                Map.of(
                    "method", "POST",
                    "path", "/tenants/${env.tenant}/tasks/${task.externalTaskId}/cases/${case.customerId}/orders/${suite.order.id}/items/${suite.items[0].id}/step/${step.create-order.orderId}/pay",
                    "query", Map.of("task", "${task.externalTaskId}", "customer", "${case.customerId}"),
                    "headers", Map.of("X-Trace-Id", "trace-${step.create-order.orderId}", "X-Tenant", "${env.tenant}"),
                    "body", Map.of(
                        "quantity", "${suite.order.quantity}",
                        "paid", "${case.shouldPay}",
                        "orderCopy", "${suite.order}",
                        "itemsCopy", "${suite.items}",
                        "note", "tenant-${env.tenant}-customer-${case.customerId}"
                    )
                ),
                200,
                List.of()
            )
        ));
        suiteCase.setDetail(Map.of("suite", true, "customerId", "cust-9", "shouldPay", true));
        var suite = testCases.save(suiteCase);
        fakeHttpClient.respondWithSequence(
            new HttpClientResponse(201, Map.of(), Map.of(
                "data", Map.of(
                    "order", Map.of("id", "order-123", "quantity", 2),
                    "items", List.of(Map.of("id", "item-1"), Map.of("id", "item-2"))
                )
            ), 8L),
            new HttpClientResponse(200, Map.of(), Map.of("paid", true), 6L)
        );

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults(),
            Map.of("tenant", "tenant-a"),
            Map.of()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.PASSED);
        assertThat(fakeHttpClient.requests()).hasSize(2);
        var payRequest = fakeHttpClient.requests().get(1);
        assertThat(payRequest.path()).isEqualTo("/tenants/tenant-a/tasks/task-42/cases/cust-9/orders/order-123/items/item-1/step/order-123/pay");
        assertThat(payRequest.queryParams()).containsEntry("task", "task-42").containsEntry("customer", "cust-9");
        assertThat(payRequest.headers()).containsEntry("X-Trace-Id", "trace-order-123").containsEntry("X-Tenant", "tenant-a");
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) payRequest.body();
        assertThat(body.get("quantity")).isEqualTo(2);
        assertThat(body.get("paid")).isEqualTo(true);
        assertThat(body.get("orderCopy")).isInstanceOf(Map.class);
        assertThat(body.get("itemsCopy")).isInstanceOf(List.class);
        assertThat(body.get("note")).isEqualTo("tenant-tenant-a-customer-cust-9");
    }

    @Test
    void variableResolverBlocksMissingInvalidScopeAndInvalidExpressionBeforeTransport() {
        var apiSpec = apiSpecs.save(newApiSpec(HttpMethod.GET, "/api/orders", 200));
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var missingVariableSuite = testCases.save(newSuiteTestCase(apiSpec.getApiSpecId(), List.of(
            suiteStepWithTemplate(1, "missing-variable", apiSpec.getApiSpecId(), Map.of(
                "method", "GET",
                "path", "/api/orders/${suite.missingOrderId}"
            ), 200, List.of())
        )));
        var invalidScopeSuite = testCases.save(newSuiteTestCase(apiSpec.getApiSpecId(), List.of(
            suiteStepWithTemplate(1, "invalid-scope", apiSpec.getApiSpecId(), Map.of(
                "method", "GET",
                "path", "/api/orders/${window.location}"
            ), 200, List.of())
        )));
        var invalidExpressionSuite = testCases.save(newSuiteTestCase(apiSpec.getApiSpecId(), List.of(
            suiteStepWithTemplate(1, "invalid-expression", apiSpec.getApiSpecId(), Map.of(
                "method", "GET",
                "path", "/api/orders/${suite.orderId + system.exit()}"
            ), 200, List.of())
        )));

        var missing = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(missingVariableSuite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));
        var invalidScope = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(invalidScopeSuite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));
        var invalidExpression = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(invalidExpressionSuite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(fakeHttpClient.requests()).isEmpty();
        assertThat(missing.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.BLOCKED);
        assertThat(invalidScope.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.BLOCKED);
        assertThat(invalidExpression.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.BLOCKED);
        assertRuntimeDiagnostic(missing.caseResults().getFirst().executionRecordId(), "PATH_MISSING", "missing-variable");
        assertRuntimeDiagnostic(invalidScope.caseResults().getFirst().executionRecordId(), "UNSUPPORTED_VARIABLE_SCOPE", "invalid-scope");
        assertRuntimeDiagnostic(invalidExpression.caseResults().getFirst().executionRecordId(), "INVALID_VARIABLE_EXPRESSION", "invalid-expression");
    }

    @Test
    void dryRunResolvesSuiteRequestTemplateWithoutCallingTransport() {
        var apiSpec = apiSpecs.save(newApiSpec(HttpMethod.GET, "/api/orders", 200));
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var suite = testCases.save(newSuiteTestCase(apiSpec.getApiSpecId(), List.of(
            suiteStepWithTemplate(1, "dry-run-step", apiSpec.getApiSpecId(), Map.of(
                "method", "GET",
                "path", "/api/${env.tenant}/orders",
                "headers", Map.of("X-Tenant", "${env.tenant}")
            ), 200, List.of())
        )));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            true,
            HttpExecutionOptions.defaults(),
            Map.of("tenant", "tenant-a"),
            Map.of()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.SKIPPED);
        assertThat(fakeHttpClient.requests()).isEmpty();
        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) record.getResponseSnapshot().get("steps");
        @SuppressWarnings("unchecked")
        var requestSnapshot = (Map<String, Object>) steps.getFirst().get("requestSnapshot");
        assertThat(requestSnapshot).containsEntry("path", "/api/tenant-a/orders");
        @SuppressWarnings("unchecked")
        var headers = (Map<String, Object>) requestSnapshot.get("headers");
        assertThat(headers).containsEntry("X-Tenant", "tenant-a");
    }

    @Test
    void dynamicValueProviderResolvesDeterministicFunctionsAcrossRequestTemplate() {
        var apiSpec = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/users", 201));
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var suite = testCases.save(newSuiteTestCase(apiSpec.getApiSpecId(), List.of(
            suiteStepWithTemplate(1, "create-user", apiSpec.getApiSpecId(), Map.of(
                "method", "POST",
                "path", "/api/users/${fn.uuid()}",
                "query", Map.of("createdAt", "${fn.now()}", "plan", "${data.randomFrom('BASIC','PREMIUM')}"),
                "headers", Map.of("X-Test-Email", "${data.randomEmail()}"),
                "body", Map.of(
                    "userId", "${fn.uuid()}",
                    "email", "${data.randomEmail()}",
                    "phone", "${data.randomPhone()}",
                    "label", "user-${data.randomFrom(alpha,beta)}"
                )
            ), 201, List.of())
        )));
        fakeHttpClient.respondWith(new HttpClientResponse(201, Map.of(), Map.of("ok", true), 4L));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.PASSED);
        assertThat(fakeHttpClient.requests()).singleElement()
            .satisfies(request -> {
                assertThat(request.path()).isEqualTo("/api/users/00000000-0000-4000-8000-000000000001");
                assertThat(request.queryParams())
                    .containsEntry("createdAt", "2026-01-02T03:04:05Z")
                    .containsEntry("plan", "BASIC");
                assertThat(request.headers()).containsEntry("X-Test-Email", "probe.user@example.test");
                @SuppressWarnings("unchecked")
                var body = (Map<String, Object>) request.body();
                assertThat(body)
                    .containsEntry("userId", "00000000-0000-4000-8000-000000000001")
                    .containsEntry("email", "probe.user@example.test")
                    .containsEntry("phone", "15500000000")
                    .containsEntry("label", "user-alpha");
            });
        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var auditSummary = (Map<String, Object>) record.getResponseSnapshot().get("variableAuditSummary");
        @SuppressWarnings("unchecked")
        var events = (List<Map<String, Object>>) auditSummary.get("events");
        assertThat(events)
            .filteredOn(event -> "DYNAMIC_VALUE_PROVIDER".equals(event.get("valueSource")))
            .hasSize(8);
    }

    @Test
    void unsupportedDynamicFunctionReturnsDiagnosticWithoutSideEffects() {
        var apiSpec = apiSpecs.save(newApiSpec(HttpMethod.GET, "/api/users", 200));
        var task = tasks.save(newTask(apiSpec.getApiSpecId()));
        var suite = testCases.save(newSuiteTestCase(apiSpec.getApiSpecId(), List.of(
            suiteStepWithTemplate(1, "unsafe-function", apiSpec.getApiSpecId(), Map.of(
                "method", "GET",
                "path", "/api/users/${fn.exec('rm -rf /')}"
            ), 200, List.of())
        )));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.BLOCKED);
        assertThat(fakeHttpClient.requests()).isEmpty();
        assertRuntimeDiagnostic(result.caseResults().getFirst().executionRecordId(), "UNSUPPORTED_DYNAMIC_FUNCTION", "unsafe-function");
    }

    @Test
    void responseExtractorReadsBodyJsonHeaderAndStatusCodeIntoRuntimeContext() {
        var createOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders", 201));
        var readOrder = apiSpecs.save(newApiSpec(HttpMethod.GET, "/api/orders/order-456/items/item-7", 200));
        var task = tasks.save(newTask(List.of(createOrder.getApiSpecId(), readOrder.getApiSpecId())));
        var suite = testCases.save(newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStepWithRuntime(
                1,
                "create-order",
                createOrder.getApiSpecId(),
                "POST",
                "/api/orders",
                201,
                List.of(
                    Map.of(
                        "sourceType", "BODY_JSON",
                        "sourcePath", "$.data.order.id",
                        "targetScope", "SUITE",
                        "targetKey", "orderId",
                        "required", true,
                        "failureStrategy", "FAIL_FAST"
                    ),
                    Map.of(
                        "sourceType", "BODY_JSON",
                        "sourcePath", "$.data.items[0].id",
                        "targetScope", "SUITE",
                        "targetKey", "itemId",
                        "required", true,
                        "failureStrategy", "FAIL_FAST"
                    ),
                    Map.of(
                        "sourceType", "HEADER",
                        "sourcePath", "headers.X-Trace-Id",
                        "targetScope", "SUITE",
                        "targetKey", "traceId",
                        "required", true,
                        "failureStrategy", "FAIL_FAST"
                    ),
                    Map.of(
                        "sourceType", "STATUS_CODE",
                        "targetScope", "SUITE",
                        "targetKey", "createStatus",
                        "required", true,
                        "failureStrategy", "FAIL_FAST"
                    )
                )
            ),
            suiteStepWithTemplate(
                2,
                "read-order",
                readOrder.getApiSpecId(),
                Map.of(
                    "method", "GET",
                    "path", "/api/orders/${suite.orderId}/items/${suite.itemId}",
                    "query", Map.of("status", "${suite.createStatus}"),
                    "headers", Map.of("X-Trace-Id", "${suite.traceId}")
                ),
                200,
                List.of()
            )
        )));
        fakeHttpClient.respondWithSequence(
            new HttpClientResponse(201, Map.of("x-trace-id", "trace-abc"), Map.of(
                "data", Map.of(
                    "order", Map.of("id", "order-456"),
                    "items", List.of(Map.of("id", "item-7"))
                )
            ), 13L),
            new HttpClientResponse(200, Map.of(), Map.of("ok", true), 5L)
        );

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.PASSED);
        assertThat(fakeHttpClient.requests()).hasSize(2);
        var readRequest = fakeHttpClient.requests().get(1);
        assertThat(readRequest.path()).isEqualTo("/api/orders/order-456/items/item-7");
        assertThat(readRequest.queryParams()).containsEntry("status", 201);
        assertThat(readRequest.headers()).containsEntry("X-Trace-Id", "trace-abc");

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var auditSummary = (Map<String, Object>) record.getResponseSnapshot().get("variableAuditSummary");
        assertThat(auditSummary).containsEntry("productionEvents", 4L);
        @SuppressWarnings("unchecked")
        var events = (List<Map<String, Object>>) auditSummary.get("events");
        assertThat(events)
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "PRODUCTION")
                .containsEntry("sourceType", "BODY_JSON")
                .containsEntry("sourcePath", "$.data.order.id")
                .containsEntry("targetKey", "orderId"))
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "PRODUCTION")
                .containsEntry("sourceType", "BODY_JSON")
                .containsEntry("sourcePath", "$.data.items[0].id")
                .containsEntry("targetKey", "itemId"))
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "PRODUCTION")
                .containsEntry("sourceType", "HEADER")
                .containsEntry("sourcePath", "X-Trace-Id")
                .containsEntry("targetKey", "traceId"))
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "PRODUCTION")
                .containsEntry("sourceType", "STATUS_CODE")
                .containsEntry("sourcePath", "statusCode")
                .containsEntry("targetKey", "createStatus"));
    }

    @Test
    void responseExtractorFallbackStrategiesWriteNullAndDefaultValues() {
        var createOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders", 201));
        var readOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/fallback/fallback-id", 200));
        var task = tasks.save(newTask(List.of(createOrder.getApiSpecId(), readOrder.getApiSpecId())));
        var suite = testCases.save(newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStepWithRuntime(
                1,
                "create-order",
                createOrder.getApiSpecId(),
                "POST",
                "/api/orders",
                201,
                List.of(
                    Map.of(
                        "sourceType", "BODY_JSON",
                        "sourcePath", "$.data.optionalToken",
                        "targetScope", "SUITE",
                        "targetKey", "optionalToken",
                        "required", false,
                        "failureStrategy", "WRITE_NULL"
                    ),
                    Map.of(
                        "sourceType", "BODY_JSON",
                        "sourcePath", "$.data.defaultId",
                        "targetScope", "SUITE",
                        "targetKey", "defaultId",
                        "required", false,
                        "failureStrategy", "WRITE_DEFAULT",
                        "defaultValue", "fallback-id"
                    )
                )
            ),
            suiteStepWithTemplate(
                2,
                "read-fallback",
                readOrder.getApiSpecId(),
                Map.of(
                    "method", "POST",
                    "path", "/api/fallback/${suite.defaultId}",
                    "body", Map.of(
                        "optionalToken", "${suite.optionalToken}",
                        "defaultId", "${suite.defaultId}"
                    )
                ),
                200,
                List.of()
            )
        )));
        fakeHttpClient.respondWithSequence(
            new HttpClientResponse(201, Map.of(), Map.of("data", Map.of("created", true)), 7L),
            new HttpClientResponse(200, Map.of(), Map.of("ok", true), 3L)
        );

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.PASSED);
        assertThat(fakeHttpClient.requests()).hasSize(2);
        var readRequest = fakeHttpClient.requests().get(1);
        assertThat(readRequest.path()).isEqualTo("/api/fallback/fallback-id");
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) readRequest.body();
        assertThat(body).containsEntry("optionalToken", null).containsEntry("defaultId", "fallback-id");

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var auditSummary = (Map<String, Object>) record.getResponseSnapshot().get("variableAuditSummary");
        @SuppressWarnings("unchecked")
        var events = (List<Map<String, Object>>) auditSummary.get("events");
        assertThat(events)
            .anySatisfy(event -> {
                assertThat(event)
                    .containsEntry("eventType", "PRODUCTION")
                    .containsEntry("targetKey", "optionalToken");
                @SuppressWarnings("unchecked")
                var summary = (Map<String, Object>) event.get("newValueSummary");
                assertThat(summary)
                    .containsEntry("type", "null")
                    .containsEntry("value", "[REDACTED]")
                    .containsEntry("redacted", true);
            })
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "PRODUCTION")
                .containsEntry("targetKey", "defaultId"));
    }

    @Test
    void responseExtractorBlocksRequiredFailuresInvalidRulesAndUnsupportedSources() {
        var createOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders", 201));
        var readOrder = apiSpecs.save(newApiSpec(HttpMethod.GET, "/api/orders/missing", 200));
        var task = tasks.save(newTask(List.of(createOrder.getApiSpecId(), readOrder.getApiSpecId())));
        var requiredFailureSuite = testCases.save(newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStepWithRuntime(
                1,
                "create-order",
                createOrder.getApiSpecId(),
                "POST",
                "/api/orders",
                201,
                List.of(Map.of(
                    "sourceType", "BODY_JSON",
                    "sourcePath", "$.data.orderId",
                    "targetScope", "SUITE",
                    "targetKey", "orderId",
                    "required", true,
                    "failureStrategy", "FAIL_FAST"
                ))
            ),
            suiteStepWithTemplate(
                2,
                "read-order",
                readOrder.getApiSpecId(),
                Map.of("method", "GET", "path", "/api/orders/${suite.orderId}"),
                200,
                List.of()
            )
        )));
        fakeHttpClient.respondWith(new HttpClientResponse(201, Map.of(), Map.of("data", Map.of("created", true)), 6L));

        var requiredFailure = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(requiredFailureSuite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(requiredFailure.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.BLOCKED);
        assertThat(fakeHttpClient.requests()).extracting(HttpClientRequest::path).containsExactly("/api/orders");
        assertRuntimeDiagnostic(requiredFailure.caseResults().getFirst().executionRecordId(), "PATH_MISSING", "create-order");
        var requiredFailureRecord = executionRecords.findById(requiredFailure.caseResults().getFirst().executionRecordId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var requiredFailureSteps = (List<Map<String, Object>>) requiredFailureRecord.getResponseSnapshot().get("steps");
        assertThat(requiredFailureSteps).extracting(step -> step.get("status")).containsExactly("BLOCKED", "SKIPPED");
        assertThat(requiredFailureSteps.get(1).get("message")).asString().contains("runtime variable failure");

        fakeHttpClient.reset();
        var invalidRule = new java.util.LinkedHashMap<String, Object>();
        invalidRule.put("sourceType", "BODY_JSON");
        invalidRule.put("sourcePath", "$.data.orderId");
        invalidRule.put("targetKey", "orderId");
        invalidRule.put("required", true);
        invalidRule.put("failureStrategy", "FAIL_FAST");
        var unsupportedRule = new java.util.LinkedHashMap<String, Object>();
        unsupportedRule.put("sourceType", "XML");
        unsupportedRule.put("sourcePath", "/order/id");
        unsupportedRule.put("targetScope", "SUITE");
        unsupportedRule.put("targetKey", "xmlOrderId");
        unsupportedRule.put("required", true);
        unsupportedRule.put("failureStrategy", "FAIL_FAST");
        var invalidRuleSuite = testCases.save(newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStepWithRuntime(
                1,
                "invalid-extract-rule",
                createOrder.getApiSpecId(),
                "POST",
                "/api/orders",
                201,
                List.of(invalidRule, unsupportedRule)
            )
        )));
        fakeHttpClient.respondWith(new HttpClientResponse(201, Map.of(), Map.of("data", Map.of("orderId", "order-789")), 4L));

        var invalidRuleResult = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(invalidRuleSuite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(invalidRuleResult.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.BLOCKED);
        assertThat(fakeHttpClient.requests()).extracting(HttpClientRequest::path).containsExactly("/api/orders");
        assertRuntimeDiagnostic(invalidRuleResult.caseResults().getFirst().executionRecordId(), "INVALID_EXTRACT_RULE", "invalid-extract-rule");
        assertRuntimeDiagnostic(invalidRuleResult.caseResults().getFirst().executionRecordId(), "UNSUPPORTED_EXTRACT_SOURCE", "invalid-extract-rule");
    }

    @Test
    void variableWriteBackAuditsSuiteStepOverwriteFallbackAndConsumptionLifecycle() {
        var createOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders", 201));
        var readOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders/order-2/step/order-1", 200));
        var task = tasks.save(newTask(List.of(createOrder.getApiSpecId(), readOrder.getApiSpecId())));
        var suite = testCases.save(newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStepWithRuntime(
                1,
                "create-order",
                createOrder.getApiSpecId(),
                "POST",
                "/api/orders",
                201,
                List.of(
                    Map.of(
                        "sourceType", "BODY_JSON",
                        "sourcePath", "$.data.orderId",
                        "targetScope", "SUITE",
                        "targetKey", "orderId",
                        "required", true,
                        "failureStrategy", "FAIL_FAST"
                    ),
                    Map.of(
                        "sourceType", "BODY_JSON",
                        "sourcePath", "$.data.replacementOrderId",
                        "targetScope", "SUITE",
                        "targetKey", "orderId",
                        "required", true,
                        "failureStrategy", "FAIL_FAST"
                    ),
                    Map.of(
                        "sourceType", "BODY_JSON",
                        "sourcePath", "$.data.orderId",
                        "targetScope", "STEP",
                        "targetKey", "orderId",
                        "required", true,
                        "failureStrategy", "FAIL_FAST"
                    ),
                    Map.of(
                        "sourceType", "BODY_JSON",
                        "sourcePath", "$.data.optionalValue",
                        "targetScope", "SUITE",
                        "targetKey", "optionalValue",
                        "required", false,
                        "failureStrategy", "WRITE_NULL"
                    ),
                    Map.of(
                        "sourceType", "BODY_JSON",
                        "sourcePath", "$.data.fallbackStatus",
                        "targetScope", "SUITE",
                        "targetKey", "fallbackStatus",
                        "required", false,
                        "failureStrategy", "WRITE_DEFAULT",
                        "defaultValue", "UNKNOWN"
                    )
                )
            ),
            suiteStepWithTemplate(
                2,
                "read-order",
                readOrder.getApiSpecId(),
                Map.of(
                    "method", "POST",
                    "path", "/api/orders/${suite.orderId}/step/${step.create-order.orderId}",
                    "body", Map.of(
                        "optionalValue", "${suite.optionalValue}",
                        "fallbackStatus", "${suite.fallbackStatus}"
                    )
                ),
                200,
                List.of()
            )
        )));
        fakeHttpClient.respondWithSequence(
            new HttpClientResponse(201, Map.of(), Map.of(
                "data", Map.of(
                    "orderId", "order-1",
                    "replacementOrderId", "order-2"
                )
            ), 10L),
            new HttpClientResponse(200, Map.of(), Map.of("ok", true), 4L)
        );

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.PASSED);
        assertThat(fakeHttpClient.requests()).hasSize(2);
        var readRequest = fakeHttpClient.requests().get(1);
        assertThat(readRequest.path()).isEqualTo("/api/orders/order-2/step/order-1");
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) readRequest.body();
        assertThat(body).containsEntry("optionalValue", null).containsEntry("fallbackStatus", "UNKNOWN");

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var responseSnapshot = record.getResponseSnapshot();
        @SuppressWarnings("unchecked")
        var auditSummary = (Map<String, Object>) responseSnapshot.get("variableAuditSummary");
        assertThat(auditSummary)
            .containsEntry("productionEvents", 5L)
            .containsEntry("consumptionEvents", 4L)
            .containsEntry("failureEvents", 0L);
        @SuppressWarnings("unchecked")
        var events = (List<Map<String, Object>>) auditSummary.get("events");
        assertThat(events).extracting(event -> event.get("sequence"))
            .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(events)
            .anySatisfy(event -> {
                assertThat(event)
                    .containsEntry("eventType", "PRODUCTION")
                    .containsEntry("targetScope", "suite")
                    .containsEntry("targetKey", "orderId")
                    .containsEntry("overwritten", true)
                    .containsEntry("valueOrigin", "EXTRACTED");
                @SuppressWarnings("unchecked")
                var oldSummary = (Map<String, Object>) event.get("oldValueSummary");
                @SuppressWarnings("unchecked")
                var newSummary = (Map<String, Object>) event.get("newValueSummary");
                assertThat(oldSummary).containsEntry("value", "order-1");
                assertThat(newSummary).containsEntry("value", "order-2");
            })
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "PRODUCTION")
                .containsEntry("targetScope", "step")
                .containsEntry("targetKey", "orderId")
                .containsEntry("newValueSummary", Map.of("type", "string", "value", "order-1", "truncated", false, "redacted", false)))
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "PRODUCTION")
                .containsEntry("targetKey", "optionalValue")
                .containsEntry("fallbackApplied", true)
                .containsEntry("valueOrigin", "WRITE_NULL"))
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "PRODUCTION")
                .containsEntry("targetKey", "fallbackStatus")
                .containsEntry("fallbackApplied", true)
                .containsEntry("valueOrigin", "WRITE_DEFAULT"))
            .anySatisfy(event -> assertThat(event)
                .containsEntry("eventType", "CONSUMPTION")
                .containsEntry("stepId", "read-order")
                .containsEntry("expression", "${step.create-order.orderId}")
                .containsEntry("resolved", true));

        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) responseSnapshot.get("steps");
        assertThat(steps.get(0).get("contextAfter").toString()).contains("order-2").contains("fallbackStatus=UNKNOWN");
        assertThat(steps.get(1).get("contextBefore").toString()).contains("order-2").contains("order-1");
    }

    @Test
    void variableWriteBackFailuresAndSensitiveValuesAreAuditedWithRedaction() {
        var createOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders", 201));
        var task = tasks.save(newTask(createOrder.getApiSpecId()));
        var unsupportedWriteRule = new java.util.LinkedHashMap<String, Object>();
        unsupportedWriteRule.put("sourceType", "BODY_JSON");
        unsupportedWriteRule.put("sourcePath", "$.data.authToken");
        unsupportedWriteRule.put("targetScope", "GLOBAL");
        unsupportedWriteRule.put("targetKey", "leakedCredential");
        unsupportedWriteRule.put("required", true);
        unsupportedWriteRule.put("failureStrategy", "FAIL_FAST");
        var suite = testCases.save(newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStepWithRuntime(
                1,
                "create-order",
                createOrder.getApiSpecId(),
                "POST",
                "/api/orders",
                201,
                List.of(
                    Map.of(
                        "sourceType", "BODY_JSON",
                        "sourcePath", "$.data.authToken",
                        "targetScope", "SUITE",
                        "targetKey", "authToken",
                        "required", true,
                        "failureStrategy", "FAIL_FAST"
                    ),
                    unsupportedWriteRule
                )
            )
        )));
        fakeHttpClient.respondWith(new HttpClientResponse(
            201,
            Map.of("Authorization", "Bearer secret-token", "X-Trace-Id", "trace-1"),
            Map.of("data", Map.of(
                "authToken", "secret-token",
                "password", "plain-password"
            )),
            9L
        ));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.BLOCKED);
        assertRuntimeDiagnostic(result.caseResults().getFirst().executionRecordId(), "UNSUPPORTED_WRITE_SCOPE", "create-order");
        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getResponseSnapshot().toString())
            .doesNotContain("secret-token")
            .doesNotContain("plain-password")
            .contains("[REDACTED]");

        @SuppressWarnings("unchecked")
        var auditSummary = (Map<String, Object>) record.getResponseSnapshot().get("variableAuditSummary");
        assertThat(auditSummary).containsEntry("failureEvents", 1L);
        @SuppressWarnings("unchecked")
        var events = (List<Map<String, Object>>) auditSummary.get("events");
        assertThat(events)
            .anySatisfy(event -> {
                assertThat(event)
                    .containsEntry("eventType", "PRODUCTION")
                    .containsEntry("targetKey", "authToken")
                    .containsEntry("success", true);
                @SuppressWarnings("unchecked")
                var summary = (Map<String, Object>) event.get("newValueSummary");
                assertThat(summary).containsEntry("value", "[REDACTED]").containsEntry("redacted", true);
            })
            .anySatisfy(event -> {
                assertThat(event)
                    .containsEntry("eventType", "PRODUCTION")
                    .containsEntry("targetScope", "global")
                    .containsEntry("targetKey", "leakedCredential")
                    .containsEntry("success", false)
                    .containsEntry("failureReason", "UNSUPPORTED_WRITE_SCOPE")
                    .containsEntry("blockingFailure", true);
                @SuppressWarnings("unchecked")
                var attempted = (Map<String, Object>) event.get("attemptedValueSummary");
                assertThat(attempted).containsEntry("value", "[REDACTED]").containsEntry("redacted", true);
            });
    }

    @Test
    void suiteDiagnosticsMarkMissingVariableBlockedAndSkipDependentsWithCause() {
        var createOrder = apiSpecs.save(newApiSpec(HttpMethod.GET, "/api/orders/missing", 200));
        var readOrder = apiSpecs.save(newApiSpec(HttpMethod.GET, "/api/orders/after", 200));
        var task = tasks.save(newTask(List.of(createOrder.getApiSpecId(), readOrder.getApiSpecId())));
        var suite = testCases.save(newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStepWithTemplate(
                1,
                "create-order",
                createOrder.getApiSpecId(),
                Map.of("method", "GET", "path", "/api/orders/${suite.orderId}"),
                200,
                List.of()
            ),
            suiteStepWithTemplate(
                2,
                "read-order",
                readOrder.getApiSpecId(),
                Map.of("method", "GET", "path", "/api/orders/after"),
                200,
                List.of()
            )
        )));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.BLOCKED);
        assertThat(fakeHttpClient.requests()).isEmpty();
        assertRuntimeDiagnostic(result.caseResults().getFirst().executionRecordId(), "PATH_MISSING", "create-order");
        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) record.getResponseSnapshot().get("steps");
        assertThat(steps).extracting(step -> step.get("status")).containsExactly("BLOCKED", "SKIPPED");
        assertThat(steps.get(1).get("message")).asString()
            .contains("runtime variable failure")
            .contains("diagnostic=PATH_MISSING")
            .contains("variable=suite.orderId");
    }

    @Test
    void suiteDiagnosticsMarkRequiredExtractionFailureAndSkipDependentsWithCause() {
        var createOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders", 201));
        var readOrder = apiSpecs.save(newApiSpec(HttpMethod.GET, "/api/orders/missing", 200));
        var task = tasks.save(newTask(List.of(createOrder.getApiSpecId(), readOrder.getApiSpecId())));
        var suite = testCases.save(newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStepWithRuntime(
                1,
                "create-order",
                createOrder.getApiSpecId(),
                "POST",
                "/api/orders",
                201,
                List.of(Map.of(
                    "sourceType", "BODY_JSON",
                    "sourcePath", "$.data.orderId",
                    "targetScope", "SUITE",
                    "targetKey", "orderId",
                    "required", true,
                    "failureStrategy", "FAIL_FAST"
                ))
            ),
            suiteStepWithTemplate(
                2,
                "read-order",
                readOrder.getApiSpecId(),
                Map.of("method", "GET", "path", "/api/orders/${suite.orderId}"),
                200,
                List.of()
            )
        )));
        fakeHttpClient.respondWith(new HttpClientResponse(201, Map.of(), Map.of("data", Map.of("created", true)), 5L));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.BLOCKED);
        assertThat(fakeHttpClient.requests()).extracting(HttpClientRequest::path).containsExactly("/api/orders");
        assertRuntimeDiagnostic(result.caseResults().getFirst().executionRecordId(), "PATH_MISSING", "create-order");
        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) record.getResponseSnapshot().get("steps");
        assertThat(steps).extracting(step -> step.get("status")).containsExactly("BLOCKED", "SKIPPED");
        assertThat(steps.get(0).get("responseSnapshot").toString()).contains("VARIABLE_EXTRACTION_FAILED");
        assertThat(steps.get(1).get("message")).asString()
            .contains("diagnostic=PATH_MISSING")
            .contains("variable=suite.orderId");
    }

    @Test
    void dryRunResolvesVariablesButSkipsTransportExtractionWriteBackAndProductionAudit() {
        var createOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders", 201));
        var task = tasks.save(newTask(createOrder.getApiSpecId()));
        var suite = testCases.save(newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStepWithRuntime(
                1,
                "create-order",
                createOrder.getApiSpecId(),
                "POST",
                "/api/${env.tenant}/orders",
                201,
                List.of(Map.of(
                    "sourceType", "BODY_JSON",
                    "sourcePath", "$.data.orderId",
                    "targetScope", "SUITE",
                    "targetKey", "orderId",
                    "required", true,
                    "failureStrategy", "FAIL_FAST"
                ))
            )
        )));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            true,
            HttpExecutionOptions.defaults(),
            Map.of("tenant", "tenant-a"),
            Map.of()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.SKIPPED);
        assertThat(fakeHttpClient.requests()).isEmpty();
        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var responseSnapshot = record.getResponseSnapshot();
        @SuppressWarnings("unchecked")
        var auditSummary = (Map<String, Object>) responseSnapshot.get("variableAuditSummary");
        assertThat(auditSummary)
            .containsEntry("productionEvents", 0L)
            .containsEntry("consumptionEvents", 1L)
            .containsEntry("failureEvents", 0L);
        assertThat(responseSnapshot.get("contextSummary").toString()).doesNotContain("orderId");
        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) responseSnapshot.get("steps");
        @SuppressWarnings("unchecked")
        var requestSnapshot = (Map<String, Object>) steps.getFirst().get("requestSnapshot");
        assertThat(requestSnapshot).containsEntry("path", "/api/tenant-a/orders");
        assertThat(steps.getFirst().get("responseSnapshot").toString()).contains("Dry run prepared request");
    }

    @Test
    void suiteExecutionContinuesAfterHttpFailureWhenStopOnCriticalFailureIsDisabled() {
        var createOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders", 201));
        var readOrder = apiSpecs.save(newApiSpec(HttpMethod.GET, "/api/orders/after", 200));
        var task = tasks.save(newTask(List.of(createOrder.getApiSpecId(), readOrder.getApiSpecId())));
        var suite = testCases.save(newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStep(1, "create-order", createOrder.getApiSpecId(), "POST", "/api/orders", 201),
            suiteStep(2, "read-order", readOrder.getApiSpecId(), "GET", "/api/orders/after", 200)
        )));
        fakeHttpClient.respondWithSequence(
            new HttpClientResponse(500, Map.of(), Map.of("error", "boom"), 11L),
            new HttpClientResponse(200, Map.of(), Map.of("ok", true), 3L)
        );

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            HttpExecutionOptions.defaults()
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.FAILED);
        assertThat(fakeHttpClient.requests()).extracting(HttpClientRequest::path)
            .containsExactly("/api/orders", "/api/orders/after");
        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) record.getResponseSnapshot().get("steps");
        assertThat(steps).extracting(step -> step.get("status")).containsExactly("FAILED", "PASSED");
    }

    @Test
    void suiteStopsDependentStepsAfterFailedPrerequisiteWhenConfigured() {
        var createOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders", 201));
        var readOrder = apiSpecs.save(newApiSpec(HttpMethod.GET, "/api/orders/order-123", 200));
        var payOrder = apiSpecs.save(newApiSpec(HttpMethod.POST, "/api/orders/order-123/pay", 200));
        var task = tasks.save(newTask(List.of(createOrder.getApiSpecId(), readOrder.getApiSpecId(), payOrder.getApiSpecId())));
        var suite = testCases.save(newSuiteTestCase(createOrder.getApiSpecId(), List.of(
            suiteStep(1, "create-order", createOrder.getApiSpecId(), "POST", "/api/orders", 201),
            suiteStep(2, "read-order", readOrder.getApiSpecId(), "GET", "/api/orders/order-123", 200),
            suiteStep(3, "pay-order", payOrder.getApiSpecId(), "POST", "/api/orders/order-123/pay", 200)
        )));
        fakeHttpClient.respondWithSequence(new HttpClientResponse(500, Map.of(), Map.of("error", "boom"), 31L));

        var result = executionService.execute(new HttpExecutionRequest(
            task.getTaskId(),
            List.of(suite.getCaseId()),
            ExecutionMode.SUITE_STEP,
            "test",
            false,
            new HttpExecutionOptions(30_000L, true, true)
        ));

        assertThat(result.caseResults().getFirst().status()).isEqualTo(HttpExecutionOutcomeStatus.FAILED);
        assertThat(result.counts()).isEqualTo(new HttpExecutionCounts(1, 0, 1, 0, 0, 0));
        assertThat(fakeHttpClient.requests()).extracting(HttpClientRequest::path)
            .containsExactly("/api/orders");

        var record = executionRecords.findById(result.caseResults().getFirst().executionRecordId()).orElseThrow();
        assertThat(record.getOverallStatus()).isEqualTo(OverallStatus.FAILED);
        assertThat(record.isCriticalFailed()).isTrue();

        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>) record.getResponseSnapshot().get("steps");
        assertThat(steps).extracting(step -> step.get("status"))
            .containsExactly("FAILED", "SKIPPED", "SKIPPED");
        assertThat(steps.get(1).get("message")).asString().contains("prerequisite step failed");
        assertThat(steps.get(2).get("message")).asString().contains("prerequisite step failed");
    }

    private ApiSpec newApiSpec() {
        return newApiSpec(HttpMethod.POST, "/api/orders", 201);
    }

    private ApiSpec newApiSpec(HttpMethod method, String path, int expectedStatus) {
        var apiSpec = new ApiSpec();
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("order");
        apiSpec.setHttpMethod(method);
        apiSpec.setPath(path);
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
        return newTask(List.of(apiSpecId));
    }

    private Task newTask(List<String> apiSpecIds) {
        var task = new Task();
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Execute order API cases");
        task.setStatus(TaskStatus.EXECUTING);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("phase6-issue-01");
        task.setTargetApiSpecIds(apiSpecIds);
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.NOT_REQUIRED);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase6-test");
        task.setMetadata(Map.of("phase", "6", "issue", "01"));
        return task;
    }

    private TestCase newSingleTestCase(String apiSpecId) {
        return newSingleTestCase(apiSpecId, defaultRequestShape());
    }

    private Map<String, Object> defaultRequestShape() {
        return requestShape("/api/orders");
    }

    private Map<String, Object> requestShape(String path) {
        return Map.of(
            "method", "POST",
            "path", path,
            "headers", Map.of("Content-Type", "application/json"),
            "body", Map.of("skuId", "A-100", "quantity", 2)
        );
    }

    private TestCase newSingleTestCase(String apiSpecId, Map<String, Object> requestShape) {
        return newSingleTestCase(apiSpecId, 201, requestShape, List.of());
    }

    private TestCase newSingleTestCase(
        String apiSpecId,
        int expectedStatus,
        Map<String, Object> requestShape,
        List<Map<String, Object>> assertions
    ) {
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
        var detail = new java.util.LinkedHashMap<String, Object>();
        detail.put("expectedStatus", expectedStatus);
        detail.put("requestShape", requestShape);
        if (!assertions.isEmpty()) {
            detail.put("assertions", assertions);
        }
        testCase.setDetail(detail);
        testCase.setSteps(List.of(Map.of(
            "order", 1,
            "apiSpecId", apiSpecId,
            "expectedStatus", expectedStatus,
            "requestShape", requestShape
        )));
        testCase.setBasedOnApiSpecVersions(Map.of(apiSpecId, 3));
        return testCase;
    }

    private TestCase newSuiteTestCase(String primaryApiSpecId, List<Map<String, Object>> steps) {
        var testCase = newSingleTestCase(primaryApiSpecId);
        testCase.setMode(TestCaseMode.SUITE);
        testCase.setTitle("Order suite flow");
        testCase.setExpectedResult("Suite steps complete in order");
        testCase.setDetail(Map.of("suite", true));
        testCase.setSteps(steps);
        return testCase;
    }

    private Map<String, Object> suiteStep(
        int order,
        String stepId,
        String apiSpecId,
        String method,
        String path,
        int expectedStatus
    ) {
        return Map.of(
            "order", order,
            "stepId", stepId,
            "apiSpecId", apiSpecId,
            "method", method,
            "path", path,
            "expectedStatus", expectedStatus,
            "requestShape", Map.of(
                "method", method,
                "path", path,
                "headers", Map.of("Content-Type", "application/json")
            )
        );
    }

    private Map<String, Object> suiteStepWithRuntime(
        int order,
        String stepId,
        String apiSpecId,
        String method,
        String path,
        int expectedStatus,
        List<Map<String, Object>> extractRules
    ) {
        var step = new java.util.LinkedHashMap<String, Object>();
        step.put("order", order);
        step.put("stepId", stepId);
        step.put("apiSpecId", apiSpecId);
        step.put("method", method);
        step.put("path", path);
        step.put("expectedStatus", expectedStatus);
        step.put("requestTemplate", Map.of(
            "method", method,
            "path", path,
            "headers", Map.of("Content-Type", "application/json")
        ));
        step.put("extractRules", extractRules);
        return step;
    }

    private Map<String, Object> suiteStepWithTemplate(
        int order,
        String stepId,
        String apiSpecId,
        Map<String, Object> requestTemplate,
        int expectedStatus,
        List<Map<String, Object>> extractRules
    ) {
        var step = new java.util.LinkedHashMap<String, Object>();
        step.put("order", order);
        step.put("stepId", stepId);
        step.put("apiSpecId", apiSpecId);
        step.put("expectedStatus", expectedStatus);
        step.put("requestTemplate", requestTemplate);
        step.put("extractRules", extractRules);
        return step;
    }

    private void assertRuntimeDiagnostic(String executionRecordId, String code, String stepId) {
        var record = executionRecords.findById(executionRecordId).orElseThrow();
        @SuppressWarnings("unchecked")
        var diagnostics = (List<Map<String, Object>>) record.getResponseSnapshot().get("runtimeDiagnostics");
        assertThat(diagnostics)
            .anySatisfy(diagnostic -> assertThat(diagnostic)
                .containsEntry("code", code)
                .containsEntry("stepId", stepId));
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
