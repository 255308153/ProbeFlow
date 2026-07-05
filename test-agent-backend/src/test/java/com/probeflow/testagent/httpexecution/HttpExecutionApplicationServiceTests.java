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
        assertThat(record.getAssertionResults()).anySatisfy(assertion -> assertThat(assertion)
            .containsEntry("type", "STATUS_CODE")
            .containsEntry("expected", 201)
            .containsEntry("actual", 500)
            .containsEntry("status", "FAILED"));
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
        return newSingleTestCase(apiSpecId, defaultRequestShape());
    }

    private Map<String, Object> defaultRequestShape() {
        return Map.of(
            "method", "POST",
            "path", "/api/orders",
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

    @TestConfiguration
    static class FakeHttpClientConfiguration {

        @Bean
        @Primary
        FakeHttpClientGateway fakeHttpClientGateway() {
            return new FakeHttpClientGateway();
        }
    }
}
