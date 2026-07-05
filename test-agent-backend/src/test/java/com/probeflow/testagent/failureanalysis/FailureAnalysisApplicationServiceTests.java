package com.probeflow.testagent.failureanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.ExecutorType;
import com.probeflow.testagent.executionrecord.OverallStatus;
import com.probeflow.testagent.observation.AnalysisLevel;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.observation.ObservationRepository;
import com.probeflow.testagent.observation.ObservationRiskLevel;
import com.probeflow.testagent.observation.ObservationSource;
import com.probeflow.testagent.observation.ObservationType;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.TaskMemoryService;
import com.probeflow.testagent.task.MemoryRefinementStatus;
import com.probeflow.testagent.task.Task;
import com.probeflow.testagent.task.TaskPriority;
import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskSourceType;
import com.probeflow.testagent.task.TaskStatus;
import com.probeflow.testagent.task.TaskType;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FailureAnalysisApplicationServiceTests {

    @Autowired
    private FailureAnalysisApplicationService failureAnalysis;

    @Autowired
    private ExecutionRecordRepository executionRecords;

    @Autowired
    private ObservationRepository observations;

    @Autowired
    private ApiSpecRepository apiSpecs;

    @Autowired
    private TestCaseRepository testCases;

    @Autowired
    private TaskMemoryService taskMemoryService;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private EntityManager entityManager;

    @Test
    void singleExecutionAnalysisSummarizesPersistedExecutionFactsWithoutMutation() {
        var record = executionRecords.save(newExecutionRecord(
            OverallStatus.FAILED,
            Map.of(
                "method", "POST",
                "path", "/api/orders",
                "url", "https://api.test.example/api/orders",
                "headers", Map.of("Authorization", "[REDACTED]", "X-Tenant", "tenant-a")
            ),
            Map.of(
                "statusCode", 500,
                "failureType", "ASSERTION_FAILURE",
                "bodyType", "json",
                "bodySizeBytes", 20,
                "bodyTruncated", false,
                "headers", Map.of("Content-Type", "application/json")
            ),
            List.of(
                Map.of("name", "expected status", "type", "STATUS_CODE", "expected", 201, "actual", 500, "status", "FAILED", "critical", true),
                Map.of("name", "body present", "type", "BODY_PRESENT", "expected", true, "actual", true, "status", "PASSED", "critical", true)
            )
        ));
        entityManager.flush();
        entityManager.clear();

        var result = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(record.getExecutionId()));

        assertThat(result.executionId()).isEqualTo(record.getExecutionId());
        assertThat(result.taskId()).isEqualTo("task-1");
        assertThat(result.caseId()).isEqualTo("case-1");
        assertThat(result.mode()).isEqualTo(FailureAnalysisMode.BASIC);
        assertThat(result.overallStatus()).isEqualTo(OverallStatus.FAILED);
        assertThat(result.statusCode()).isEqualTo(500);
        assertThat(result.durationMs()).isEqualTo(123L);
        assertThat(result.environment()).isEqualTo("test");
        assertThat(result.request().method()).isEqualTo("POST");
        assertThat(result.request().path()).isEqualTo("/api/orders");
        assertThat(result.request().url()).isEqualTo("https://api.test.example/api/orders");
        assertThat(result.request().headers()).containsEntry("Authorization", "[REDACTED]");
        assertThat(result.response().statusCode()).isEqualTo(500);
        assertThat(result.response().failureType()).isEqualTo("ASSERTION_FAILURE");
        assertThat(result.response().bodyType()).isEqualTo("json");
        assertThat(result.response().bodySizeBytes()).isEqualTo(20L);
        assertThat(result.response().bodyTruncated()).isFalse();
        assertThat(result.failedAssertions()).singleElement()
            .satisfies(assertion -> {
                assertThat(assertion.name()).isEqualTo("expected status");
                assertThat(assertion.type()).isEqualTo("STATUS_CODE");
                assertThat(assertion.expected()).isEqualTo(201);
                assertThat(assertion.actual()).isEqualTo(500);
                assertThat(assertion.critical()).isTrue();
            });
        assertThat(result.classification()).isEqualTo(FailureClassification.SERVER_ERROR);
        assertThat(result.evidence()).contains("overallStatus=FAILED", "statusCode=500", "classification=SERVER_ERROR");
        assertThat(result.observationIds()).hasSize(1);
        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.summary()).contains("SERVER_ERROR");
        assertThat(result.failureReason()).contains("STATUS_CODE");

        entityManager.flush();
        entityManager.clear();
        var unchanged = executionRecords.findById(record.getExecutionId()).orElseThrow();
        assertThat(unchanged.getOverallStatus()).isEqualTo(OverallStatus.FAILED);
        assertThat(unchanged.getStatusCode()).isEqualTo(500);
        assertThat(unchanged.getRequestSnapshot()).containsEntry("path", "/api/orders");
        assertThat(unchanged.getResponseSnapshot()).containsEntry("failureType", "ASSERTION_FAILURE");
        assertThat(unchanged.getAssertionResults()).hasSize(2);
    }

    @Test
    void basicAnalysisHandlesAllExecutionStatusesWithoutThrowing() {
        var records = List.of(
            executionRecords.save(newExecutionRecord(OverallStatus.PASSED)),
            executionRecords.save(newExecutionRecord(OverallStatus.PASSED_WITH_WARNINGS)),
            executionRecords.save(newExecutionRecord(OverallStatus.FAILED)),
            executionRecords.save(newExecutionRecord(OverallStatus.ERROR)),
            executionRecords.save(newExecutionRecord(OverallStatus.BLOCKED)),
            executionRecords.save(newExecutionRecord(OverallStatus.SKIPPED))
        );
        entityManager.flush();
        entityManager.clear();

        var results = records.stream()
            .map(record -> failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(record.getExecutionId())))
            .toList();

        assertThat(results).extracting(FailureAnalysisResult::overallStatus)
            .containsExactly(
                OverallStatus.PASSED,
                OverallStatus.PASSED_WITH_WARNINGS,
                OverallStatus.FAILED,
                OverallStatus.ERROR,
                OverallStatus.BLOCKED,
                OverallStatus.SKIPPED
            );
    }

    @Test
    void missingExecutionRecordIdIsRejectedClearly() {
        assertThatThrownBy(() -> failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic("missing-execution")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ExecutionRecord not found: missing-execution");
    }

    @Test
    void deterministicClassificationCoversAssertionTransportBlockedAndStatusEvidence() {
        assertClassification(
            OverallStatus.FAILED,
            409,
            Map.of("failureType", "ASSERTION_FAILURE", "statusCode", 409),
            List.of(Map.of("type", "STATUS_CODE", "expected", 201, "actual", 409, "status", "FAILED")),
            FailureClassification.STATUS_MISMATCH
        );
        assertClassification(
            OverallStatus.FAILED,
            200,
            Map.of("statusCode", 200),
            List.of(Map.of("type", "JSON_FIELD_EXISTS", "path", "$.orderId", "expected", true, "actual", false, "status", "FAILED")),
            FailureClassification.RESPONSE_SHAPE_MISMATCH
        );
        assertClassification(
            OverallStatus.PASSED_WITH_WARNINGS,
            200,
            Map.of("statusCode", 200),
            List.of(Map.of("type", "JSON_FIELD_EQUALS", "path", "$.status", "expected", "CREATED", "actual", "PENDING", "status", "FAILED", "critical", false)),
            FailureClassification.RESPONSE_VALUE_MISMATCH
        );
        assertClassification(
            OverallStatus.FAILED,
            204,
            Map.of("statusCode", 204),
            List.of(Map.of("type", "BODY_PRESENT", "expected", true, "actual", false, "status", "FAILED")),
            FailureClassification.BODY_PRESENCE_FAILURE
        );
        assertClassification(
            OverallStatus.FAILED,
            200,
            Map.of("statusCode", 200),
            List.of(Map.of("type", "DURATION_LESS_THAN_MS", "expected", 100, "actual", 250, "status", "FAILED")),
            FailureClassification.DURATION_REGRESSION
        );
        assertClassification(
            OverallStatus.ERROR,
            null,
            Map.of("errorType", "NETWORK_ERROR"),
            List.of(),
            FailureClassification.TRANSPORT_ERROR
        );
        assertClassification(
            OverallStatus.ERROR,
            null,
            Map.of("errorType", "TIMEOUT"),
            List.of(),
            FailureClassification.TIMEOUT
        );
        assertClassification(
            OverallStatus.BLOCKED,
            null,
            Map.of("errorType", "BLOCKED_HOST"),
            List.of(),
            FailureClassification.ENVIRONMENT_ISSUE
        );
        assertClassification(OverallStatus.FAILED, 401, Map.of("statusCode", 401), List.of(), FailureClassification.AUTH_ISSUE);
        assertClassification(OverallStatus.FAILED, 403, Map.of("statusCode", 403), List.of(), FailureClassification.AUTH_ISSUE);
        assertClassification(OverallStatus.FAILED, 400, Map.of("statusCode", 400), List.of(), FailureClassification.VALIDATION_ISSUE);
        assertClassification(OverallStatus.FAILED, 422, Map.of("statusCode", 422), List.of(), FailureClassification.VALIDATION_ISSUE);
        assertClassification(OverallStatus.FAILED, 503, Map.of("statusCode", 503), List.of(), FailureClassification.SERVER_ERROR);
    }

    @Test
    void analysisPersistsMeaningfulObservationAndReusesItOnRepeatedAnalysis() {
        var record = executionRecords.save(newExecutionRecord(
            OverallStatus.FAILED,
            Map.of("method", "POST", "path", "/api/orders"),
            Map.of("statusCode", 500, "failureType", "ASSERTION_FAILURE"),
            List.of(Map.of("type", "STATUS_CODE", "expected", 201, "actual", 500, "status", "FAILED", "critical", true))
        ));
        entityManager.flush();
        entityManager.clear();

        var first = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(record.getExecutionId()));
        var second = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(record.getExecutionId()));

        assertThat(first.observationIds()).hasSize(1);
        assertThat(second.observationIds()).isEqualTo(first.observationIds());
        assertThat(observations.findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
            record.getExecutionId(),
            AnalysisLevel.BASIC
        )).singleElement()
            .satisfies(observation -> {
                assertThat(observation.getObservationId()).isEqualTo(first.observationIds().getFirst());
                assertThat(observation.getTaskId()).isEqualTo("task-1");
                assertThat(observation.getExecutionId()).isEqualTo(record.getExecutionId());
                assertThat(observation.getObservationType()).isEqualTo(ObservationType.RISK_EVALUATION);
                assertThat(observation.getAnalysisLevel()).isEqualTo(AnalysisLevel.BASIC);
                assertThat(observation.getSource()).isEqualTo(ObservationSource.SYSTEM);
                assertThat(observation.getRiskLevel()).isEqualTo(ObservationRiskLevel.HIGH);
                assertThat(observation.getSummary()).contains("SERVER_ERROR");
                assertThat(observation.getFailureReason()).contains("STATUS_CODE expected 201 but got 500");
                assertThat(observation.getNextSuggestion()).isEqualTo(first.nextSuggestion());
                assertThat(observation.getNextSuggestion()).contains("Investigate API regression");
            });

        entityManager.flush();
        entityManager.clear();
        var unchanged = executionRecords.findById(record.getExecutionId()).orElseThrow();
        assertThat(unchanged.getOverallStatus()).isEqualTo(OverallStatus.FAILED);
        assertThat(unchanged.getResponseSnapshot()).containsEntry("failureType", "ASSERTION_FAILURE");
    }

    @Test
    void passedAndSkippedExecutionsDoNotCreateNoisyObservations() {
        var passed = executionRecords.save(newExecutionRecord(OverallStatus.PASSED));
        var skipped = executionRecords.save(newExecutionRecord(OverallStatus.SKIPPED));
        entityManager.flush();
        entityManager.clear();

        var passedResult = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(passed.getExecutionId()));
        var skippedResult = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(skipped.getExecutionId()));

        assertThat(passedResult.observationIds()).isEmpty();
        assertThat(skippedResult.observationIds()).isEmpty();
        assertThat(observations.findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
            passed.getExecutionId(),
            AnalysisLevel.BASIC
        )).isEmpty();
        assertThat(observations.findAllByExecutionIdAndAnalysisLevelOrderByCreatedAtAscObservationIdAsc(
            skipped.getExecutionId(),
            AnalysisLevel.BASIC
        )).isEmpty();
    }

    @Test
    void retrySuggestionAndNextActionAreConservativeAndClassificationAware() {
        assertRecommendation(
            OverallStatus.ERROR,
            null,
            Map.of("errorType", "TIMEOUT"),
            List.of(),
            true,
            "Timeouts are often transient",
            "Retry execution"
        );
        assertRecommendation(
            OverallStatus.FAILED,
            503,
            Map.of("statusCode", 503),
            List.of(),
            true,
            "HTTP 503",
            "Retry once"
        );
        assertRecommendation(
            OverallStatus.FAILED,
            409,
            Map.of("statusCode", 409),
            List.of(Map.of("type", "STATUS_CODE", "expected", 201, "actual", 409, "status", "FAILED")),
            false,
            "deterministic or non-retryable",
            "Investigate API behavior versus TestCase expectations"
        );
        assertRecommendation(
            OverallStatus.BLOCKED,
            null,
            Map.of("errorType", "INVALID_REQUEST"),
            List.of(),
            false,
            "deterministic or non-retryable",
            "Inspect environment variables"
        );
        assertRecommendation(
            OverallStatus.FAILED,
            401,
            Map.of("statusCode", 401),
            List.of(),
            false,
            "deterministic or non-retryable",
            "Inspect auth variables"
        );
        assertRecommendation(
            OverallStatus.FAILED,
            422,
            Map.of("statusCode", 422),
            List.of(),
            false,
            "deterministic or non-retryable",
            "Review request data"
        );
        assertRecommendation(
            OverallStatus.FAILED,
            200,
            Map.of("statusCode", 200, "message", "expected payload appears stale after API drift"),
            List.of(Map.of("type", "JSON_FIELD_EQUALS", "path", "$.status", "expected", "CREATED", "actual", "PENDING", "status", "FAILED")),
            false,
            "deterministic or non-retryable",
            "update TestCase expectations"
        );
    }

    @Test
    void suiteAnalysisExplainsFirstFailedStepAndDependentSkips() {
        var record = executionRecords.save(newExecutionRecord(
            OverallStatus.FAILED,
            Map.of("suite", true, "stepCount", 3),
            Map.of("suite", true, "steps", List.of(
                suiteStep("create-order", 1, "api-create", "FAILED", "status mismatch", 500),
                suiteStep("read-order", 2, "api-read", "SKIPPED", "Skipped because prerequisite step failed: create-order", null),
                suiteStep("pay-order", 3, "api-pay", "SKIPPED", "Skipped because prerequisite step failed: create-order", null)
            )),
            List.of(Map.of("type", "STATUS_CODE", "expected", 201, "actual", 500, "status", "FAILED", "stepId", "create-order"))
        ));
        entityManager.flush();
        entityManager.clear();

        var result = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(record.getExecutionId()));

        assertThat(result.classification()).isEqualTo(FailureClassification.SUITE_PREREQUISITE_FAILURE);
        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.suiteFailure().suiteExecution()).isTrue();
        assertThat(result.suiteFailure().failedStepId()).isEqualTo("create-order");
        assertThat(result.suiteFailure().failedStepOrder()).isEqualTo(1);
        assertThat(result.suiteFailure().failedStepApiSpecId()).isEqualTo("api-create");
        assertThat(result.suiteFailure().dependentSkippedStepIds()).containsExactly("read-order", "pay-order");
        assertThat(result.suiteFailure().impactSummary()).contains("Suite prerequisite step create-order");
        assertThat(result.nextSuggestion()).contains("Inspect suite prerequisite step create-order");
        assertThat(result.evidence()).contains("firstFailedStep=create-order order=1");

        var observation = observations.findById(result.observationIds().getFirst()).orElseThrow();
        assertThat(observation.getSummary()).contains("first failing suite step create-order");
        assertThat(observation.getFailureReason()).contains("dependent skipped steps: [read-order, pay-order]");
        assertThat(observation.getNextSuggestion()).contains("downstream skipped steps");
    }

    @Test
    void suiteAnalysisHandlesMiddleStepFailureAndAllSkippedSuiteWithoutFalseIndependentFailures() {
        var middleFailed = executionRecords.save(newExecutionRecord(
            OverallStatus.FAILED,
            Map.of("suite", true, "stepCount", 3),
            Map.of("suite", true, "steps", List.of(
                suiteStep("create-order", 1, "api-create", "PASSED", null, 201),
                suiteStep("read-order", 2, "api-read", "ERROR", "Connection refused", null),
                suiteStep("pay-order", 3, "api-pay", "SKIPPED", "Skipped because prerequisite step failed: read-order", null)
            )),
            List.of()
        ));
        var allSkipped = executionRecords.save(newExecutionRecord(
            OverallStatus.SKIPPED,
            Map.of("suite", true, "stepCount", 2),
            Map.of("suite", true, "steps", List.of(
                suiteStep("create-order", 1, "api-create", "SKIPPED", "Dry run prepared request; transport not called", null),
                suiteStep("read-order", 2, "api-read", "SKIPPED", "Dry run prepared request; transport not called", null)
            )),
            List.of()
        ));
        entityManager.flush();
        entityManager.clear();

        var middleResult = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(middleFailed.getExecutionId()));
        var skippedResult = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(allSkipped.getExecutionId()));

        assertThat(middleResult.classification()).isEqualTo(FailureClassification.SUITE_PREREQUISITE_FAILURE);
        assertThat(middleResult.suiteFailure().failedStepId()).isEqualTo("read-order");
        assertThat(middleResult.suiteFailure().failedStepOrder()).isEqualTo(2);
        assertThat(middleResult.suiteFailure().dependentSkippedStepIds()).containsExactly("pay-order");
        assertThat(skippedResult.classification()).isEqualTo(FailureClassification.SKIPPED);
        assertThat(skippedResult.observationIds()).isEmpty();
        assertThat(skippedResult.suiteFailure().suiteExecution()).isTrue();
        assertThat(skippedResult.suiteFailure().failedStepId()).isNull();
        assertThat(skippedResult.suiteFailure().impactSummary()).contains("All suite steps were skipped");
    }

    @Test
    void taskAnalysisAggregatesDeduplicatesOrdersAndFlagsMissingLinkedRecords() {
        var api = apiSpecs.save(newApiSpec("/api/orders"));
        var caseOne = testCases.save(newTestCase("case-one", api.getApiSpecId()));
        var caseTwo = testCases.save(newTestCase("case-two", api.getApiSpecId()));
        var retryable = executionRecords.save(newExecutionRecord(
            OverallStatus.FAILED,
            Map.of("method", "POST", "path", "/api/orders", "apiSpecId", api.getApiSpecId()),
            Map.of("statusCode", 503),
            List.of()
        ));
        retryable.setCaseId(caseOne.getCaseId());
        retryable.setStatusCode(503);
        var duplicateOne = executionRecords.save(newExecutionRecord(
            OverallStatus.FAILED,
            Map.of("method", "POST", "path", "/api/orders", "apiSpecId", api.getApiSpecId()),
            Map.of("statusCode", 409),
            List.of(Map.of("type", "STATUS_CODE", "expected", 201, "actual", 409, "status", "FAILED"))
        ));
        duplicateOne.setCaseId(caseOne.getCaseId());
        duplicateOne.setStatusCode(409);
        var duplicateTwo = executionRecords.save(newExecutionRecord(
            OverallStatus.FAILED,
            Map.of("method", "POST", "path", "/api/orders", "apiSpecId", api.getApiSpecId()),
            Map.of("statusCode", 409),
            List.of(Map.of("type", "STATUS_CODE", "expected", 201, "actual", 409, "status", "FAILED"))
        ));
        duplicateTwo.setCaseId(caseOne.getCaseId());
        duplicateTwo.setStatusCode(409);
        var passed = executionRecords.save(newExecutionRecord(
            OverallStatus.PASSED,
            Map.of("method", "POST", "path", "/api/orders", "apiSpecId", api.getApiSpecId()),
            Map.of("statusCode", 201),
            List.of()
        ));
        passed.setCaseId(caseTwo.getCaseId());
        passed.setStatusCode(201);
        var missingLinks = executionRecords.save(newExecutionRecord(
            OverallStatus.FAILED,
            Map.of("method", "GET", "path", "/api/missing", "apiSpecId", "missing-api"),
            Map.of("statusCode", 400),
            List.of()
        ));
        missingLinks.setCaseId("missing-case");
        missingLinks.setStatusCode(400);
        entityManager.flush();
        entityManager.clear();

        var result = failureAnalysis.analyzeTask(TaskFailureAnalysisRequest.basic("task-1"));

        assertThat(result.taskId()).isEqualTo("task-1");
        assertThat(result.counts().totalExecutions()).isEqualTo(5);
        assertThat(result.counts().retryableExecutions()).isEqualTo(1);
        assertThat(result.counts().byOverallStatus()).containsEntry("PASSED", 1L).containsEntry("FAILED", 4L);
        assertThat(result.counts().byClassification()).containsEntry("SERVER_ERROR", 1L)
            .containsEntry("STATUS_MISMATCH", 2L)
            .containsEntry("VALIDATION_ISSUE", 1L)
            .containsEntry("NONE", 1L);
        assertThat(result.groupedFailures()).extracting(GroupedFailureSummary::classification)
            .containsSubsequence(FailureClassification.SERVER_ERROR, FailureClassification.STATUS_MISMATCH)
            .contains(FailureClassification.DATA_QUALITY_ISSUE);
        assertThat(result.groupedFailures()).filteredOn(group -> group.classification() == FailureClassification.STATUS_MISMATCH)
            .singleElement()
            .satisfies(group -> {
                assertThat(group.occurrenceCount()).isEqualTo(2);
                assertThat(group.executionIds()).containsExactly(duplicateOne.getExecutionId(), duplicateTwo.getExecutionId());
                assertThat(group.affectedCaseIds()).containsExactly(caseOne.getCaseId());
                assertThat(group.failedAssertionType()).isEqualTo("STATUS_CODE");
                assertThat(group.apiReference()).isEqualTo(api.getApiSpecId());
            });
        assertThat(result.groupedFailures()).filteredOn(group -> group.classification() == FailureClassification.DATA_QUALITY_ISSUE)
            .singleElement()
            .satisfies(group -> {
                assertThat(group.caseReference()).isEqualTo("missing-case");
                assertThat(group.apiReference()).isEqualTo("missing-api");
                assertThat(group.summary()).contains("TestCase missing-case", "ApiSpec missing-api");
            });

        var subset = failureAnalysis.analyzeTask(TaskFailureAnalysisRequest.basic(
            "task-1",
            List.of(duplicateOne.getExecutionId(), passed.getExecutionId())
        ));
        assertThat(subset.counts().totalExecutions()).isEqualTo(2);
        assertThat(subset.groupedFailures()).singleElement()
            .satisfies(group -> assertThat(group.executionIds()).containsExactly(duplicateOne.getExecutionId()));
    }

    @Test
    void analysisWritesCompactTaskMemoryOnceForMeaningfulExecutionsAndSkipsNoisyPassedRuns() {
        tasks.save(newTask("task-1"));
        var record = executionRecords.save(newExecutionRecord(
            OverallStatus.FAILED,
            Map.of("method", "POST", "path", "/api/orders", "apiSpecId", "api-memory"),
            Map.of("statusCode", 409),
            List.of(Map.of("type", "STATUS_CODE", "expected", 201, "actual", 409, "status", "FAILED"))
        ));
        record.setStatusCode(409);
        var passed = executionRecords.save(newExecutionRecord(
            OverallStatus.PASSED,
            Map.of("method", "GET", "path", "/api/orders", "apiSpecId", "api-memory"),
            Map.of("statusCode", 200),
            List.of()
        ));
        passed.setStatusCode(200);
        entityManager.flush();
        entityManager.clear();

        var first = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(record.getExecutionId()));
        var second = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(record.getExecutionId()));
        var passedResult = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(passed.getExecutionId()));
        var memories = taskMemoryService.readActiveTaskMemories("task-1", "execution");

        assertThat(first.taskMemoryIds()).hasSize(1);
        assertThat(second.taskMemoryIds()).isEqualTo(first.taskMemoryIds());
        assertThat(passedResult.taskMemoryIds()).isEmpty();
        assertThat(memories).singleElement()
            .satisfies(memory -> {
                assertThat(memory.memoryId()).isEqualTo(first.taskMemoryIds().getFirst());
                assertThat(memory.scopeType()).isEqualTo(MemoryScopeType.FAILURE_PATTERN);
                assertThat(memory.sourceRef()).isEqualTo(record.getExecutionId());
                assertThat(memory.summary()).contains("STATUS_MISMATCH", record.getExecutionId(), "case-1");
                assertThat(memory.content()).contains("STATUS_CODE expected 201 but got 409", "Retryable: false");
                assertThat(memory.tags()).contains("phase7", "failure-analysis", "status_mismatch");
                assertThat(memory.metadata())
                    .containsEntry("classification", "STATUS_MISMATCH")
                    .containsEntry("riskLevel", "HIGH")
                    .containsEntry("retryable", false)
                    .containsEntry("executionId", record.getExecutionId())
                    .containsEntry("caseId", "case-1")
                    .containsEntry("environment", "test")
                    .containsEntry("statusCode", 409)
                    .containsEntry("apiSpecId", "api-memory");
            });
        assertThat(tasks.findById("task-1").orElseThrow().getMemoryRefinementStatus())
            .isEqualTo(MemoryRefinementStatus.PENDING);
    }

    @Test
    void severeSingleExecutionProducesAcceptedLongTermMemoryCandidateThroughRefinery() {
        tasks.save(newTask("task-1"));
        var record = executionRecords.save(newExecutionRecord(
            OverallStatus.FAILED,
            Map.of("method", "POST", "path", "/api/orders/pay"),
            Map.of("statusCode", 503),
            List.of()
        ));
        record.setStatusCode(503);
        entityManager.flush();
        entityManager.clear();

        var result = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(record.getExecutionId()));

        assertThat(result.memoryCandidate().attempted()).isTrue();
        assertThat(result.memoryCandidate().accepted()).isTrue();
        assertThat(result.memoryCandidate().created()).isTrue();
        assertThat(result.memoryCandidate().merged()).isFalse();
        assertThat(result.memoryCandidate().rejectionReason()).isNull();
        assertThat(result.memoryCandidate().memoryId()).isNotBlank();

        var memory = longTermMemories.findById(result.memoryCandidate().memoryId()).orElseThrow();
        assertThat(memory.getSourceRef()).isEqualTo(record.getExecutionId());
        assertThat(memory.getSummary()).contains("SERVER_ERROR");
        assertThat(memory.getTags()).contains("failure-analysis", "regression-risk", "server_error");
        assertThat(memory.getMetadata())
            .containsEntry("classification", "SERVER_ERROR")
            .containsEntry("riskLevel", "HIGH")
            .containsEntry("retryable", true)
            .containsEntry("statusCode", 503)
            .containsEntry("errorCode", "SERVER_ERROR_503");
        assertThat((List<String>) memory.getMetadata().get("executionIds")).containsExactly(record.getExecutionId());
        assertThat((List<String>) memory.getMetadata().get("caseIds")).containsExactly("case-1");
        assertThat(memory.getMetadata()).containsKey("sourceEvidence");
        assertThat(tasks.findById("task-1").orElseThrow().getMemoryRefinementStatus())
            .isEqualTo(MemoryRefinementStatus.PENDING);
    }

    @Test
    void lowValueOneOffAndNoisyPassedExecutionsDoNotBecomeReusableLongTermMemory() {
        var deterministicMismatch = executionRecords.save(newExecutionRecord(
            OverallStatus.FAILED,
            Map.of("method", "POST", "path", "/api/orders"),
            Map.of("statusCode", 409),
            List.of(Map.of("type", "STATUS_CODE", "expected", 201, "actual", 409, "status", "FAILED"))
        ));
        deterministicMismatch.setCriticalFailed(false);
        deterministicMismatch.setStatusCode(409);
        var passed = executionRecords.save(newExecutionRecord(
            OverallStatus.PASSED,
            Map.of("method", "GET", "path", "/api/orders"),
            Map.of("statusCode", 200),
            List.of()
        ));
        passed.setStatusCode(200);
        entityManager.flush();
        entityManager.clear();

        var rejected = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(deterministicMismatch.getExecutionId()));
        var skipped = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(passed.getExecutionId()));

        assertThat(rejected.memoryCandidate().attempted()).isTrue();
        assertThat(rejected.memoryCandidate().accepted()).isFalse();
        assertThat(rejected.memoryCandidate().created()).isFalse();
        assertThat(rejected.memoryCandidate().rejectionReason()).isEqualTo("low-confidence");
        assertThat(rejected.memoryCandidate().memoryId()).isNull();
        assertThat(skipped.memoryCandidate().attempted()).isFalse();
        assertThat(longTermMemories.findAll()).isEmpty();
    }

    @Test
    void repeatedTaskFailuresProduceMergedLongTermMemoryCandidateThroughExistingRefinery() {
        tasks.save(newTask("task-1"));
        var first = executionRecords.save(newExecutionRecord(
            OverallStatus.FAILED,
            Map.of("method", "POST", "path", "/api/orders"),
            Map.of("statusCode", 409),
            List.of(Map.of("type", "STATUS_CODE", "expected", 201, "actual", 409, "status", "FAILED"))
        ));
        first.setStatusCode(409);
        first.setCriticalFailed(false);
        var second = executionRecords.save(newExecutionRecord(
            OverallStatus.FAILED,
            Map.of("method", "POST", "path", "/api/orders"),
            Map.of("statusCode", 409),
            List.of(Map.of("type", "STATUS_CODE", "expected", 201, "actual", 409, "status", "FAILED"))
        ));
        second.setStatusCode(409);
        second.setCriticalFailed(false);
        entityManager.flush();
        entityManager.clear();

        var firstTaskAnalysis = failureAnalysis.analyzeTask(TaskFailureAnalysisRequest.basic("task-1"));
        var secondTaskAnalysis = failureAnalysis.analyzeTask(TaskFailureAnalysisRequest.basic("task-1"));

        assertThat(firstTaskAnalysis.groupedFailures()).filteredOn(group -> group.classification() == FailureClassification.STATUS_MISMATCH)
            .singleElement()
            .satisfies(group -> assertThat(group.occurrenceCount()).isEqualTo(2));
        assertThat(firstTaskAnalysis.executionResults()).extracting(FailureAnalysisResult::memoryCandidate)
            .allSatisfy(candidate -> {
                assertThat(candidate.attempted()).isTrue();
                assertThat(candidate.accepted()).isTrue();
                assertThat(candidate.created()).isTrue();
                assertThat(candidate.memoryId()).isNotBlank();
            });
        assertThat(secondTaskAnalysis.executionResults()).extracting(FailureAnalysisResult::memoryCandidate)
            .allSatisfy(candidate -> {
                assertThat(candidate.attempted()).isTrue();
                assertThat(candidate.accepted()).isTrue();
                assertThat(candidate.created()).isFalse();
                assertThat(candidate.merged()).isTrue();
                assertThat(candidate.memoryId()).isEqualTo(firstTaskAnalysis.executionResults().getFirst().memoryCandidate().memoryId());
            });

        var memory = longTermMemories.findById(secondTaskAnalysis.executionResults().getFirst().memoryCandidate().memoryId()).orElseThrow();
        assertThat(memory.getTags()).contains("historical-failure-pattern", "status_mismatch");
        assertThat(memory.getMetadata())
            .containsEntry("classification", "STATUS_MISMATCH")
            .containsEntry("occurrenceCount", 2)
            .containsEntry("mergeCount", 2);
        assertThat((List<String>) memory.getMetadata().get("executionIds"))
            .contains(first.getExecutionId(), second.getExecutionId());
    }

    private ExecutionRecord newExecutionRecord(OverallStatus status) {
        return newExecutionRecord(
            status,
            Map.of("method", "GET", "path", "/api/orders"),
            Map.of("statusCode", 200),
            List.of()
        );
    }

    private ExecutionRecord newExecutionRecord(
        OverallStatus status,
        Map<String, Object> requestSnapshot,
        Map<String, Object> responseSnapshot,
        List<Map<String, Object>> assertionResults
    ) {
        var record = new ExecutionRecord();
        record.setTaskId("task-1");
        record.setCaseId("case-1");
        record.setExecutorType(ExecutorType.HTTP);
        record.setEnvironment("test");
        record.setRequestSnapshot(requestSnapshot);
        record.setResponseSnapshot(responseSnapshot);
        record.setAssertionResults(assertionResults);
        record.setOverallStatus(status);
        record.setCriticalFailed(status == OverallStatus.FAILED);
        record.setDurationMs(123L);
        record.setStatusCode(responseSnapshot.get("statusCode") instanceof Number number ? number.intValue() : null);
        record.setErrorMessage(status == OverallStatus.ERROR ? "Connection refused" : null);
        return record;
    }

    private ApiSpec newApiSpec(String path) {
        var apiSpec = new ApiSpec();
        apiSpec.setSystemName("order-platform");
        apiSpec.setModuleName("orders");
        apiSpec.setHttpMethod(HttpMethod.POST);
        apiSpec.setPath(path);
        apiSpec.setSummary("Orders API");
        apiSpec.setDescription("Orders API");
        apiSpec.setOperationId("orders");
        apiSpec.setParameters(Map.of());
        apiSpec.setConstraints(Map.of());
        apiSpec.setAuth(Map.of());
        apiSpec.setSourceType(ApiSpecSourceType.MANUAL);
        apiSpec.setSourceRef("manual");
        apiSpec.setSourceLocation(Map.of());
        apiSpec.setRouteReady(true);
        apiSpec.setBasicParamReady(true);
        apiSpec.setDtoExpanded(true);
        apiSpec.setValidationReady(true);
        apiSpec.setAuthReady(true);
        apiSpec.setKnowledgeContextReady(true);
        return apiSpec;
    }

    private TestCase newTestCase(String caseId, String apiSpecId) {
        var testCase = new TestCase();
        testCase.setCaseId(caseId);
        testCase.setPrimaryApiSpecId(apiSpecId);
        testCase.setCaseCategory(CaseCategory.API);
        testCase.setMode(TestCaseMode.SINGLE);
        testCase.setTitle("Generated case " + caseId);
        testCase.setDescription("Generated case");
        testCase.setPreconditions(List.of());
        testCase.setExpectedResult("Request succeeds");
        testCase.setPriority(CasePriority.MEDIUM);
        testCase.setRiskLevel(CaseRiskLevel.MEDIUM);
        testCase.setTags(List.of("phase7"));
        testCase.setStatus(CaseStatus.READY);
        testCase.setSource(CaseSource.MANUAL);
        testCase.setDetailType(DetailType.API);
        testCase.setDetail(Map.of());
        testCase.setSteps(List.of());
        testCase.setBasedOnApiSpecVersions(Map.of(apiSpecId, 1));
        testCase.setGeneratedFromSingleCaseIds(List.of());
        return testCase;
    }

    private Task newTask(String taskId) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Phase 7 task");
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("manual");
        task.setTargetApiSpecIds(List.of());
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setMemoryRefinementStatus(MemoryRefinementStatus.NOT_REQUIRED);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase7-test");
        task.setMetadata(Map.of());
        return task;
    }

    private void assertClassification(
        OverallStatus status,
        Integer statusCode,
        Map<String, Object> responseSnapshot,
        List<Map<String, Object>> assertionResults,
        FailureClassification expected
    ) {
        var response = new java.util.LinkedHashMap<String, Object>(responseSnapshot);
        if (statusCode != null) {
            response.putIfAbsent("statusCode", statusCode);
        }
        var record = newExecutionRecord(status, Map.of("method", "GET", "path", "/api/orders"), response, assertionResults);
        record.setStatusCode(statusCode);
        if (expected == FailureClassification.TIMEOUT) {
            record.setErrorMessage("Request timed out");
        }
        if (expected == FailureClassification.ENVIRONMENT_ISSUE) {
            record.setErrorMessage("Blocked host: 127.0.0.1");
        }
        record = executionRecords.save(record);
        entityManager.flush();
        entityManager.clear();

        var result = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(record.getExecutionId()));

        assertThat(result.classification()).isEqualTo(expected);
        assertThat(result.evidence()).anySatisfy(item -> assertThat(item).contains("classification=" + expected));
        if (!assertionResults.isEmpty()) {
            assertThat(result.evidence()).anySatisfy(item -> assertThat(item).contains("failedAssertion="));
        }
    }

    private Map<String, Object> suiteStep(
        String stepId,
        int order,
        String apiSpecId,
        String overallStatus,
        String message,
        Integer statusCode
    ) {
        var step = new java.util.LinkedHashMap<String, Object>();
        step.put("stepId", stepId);
        step.put("order", order);
        step.put("apiSpecId", apiSpecId);
        step.put("status", overallStatus);
        step.put("overallStatus", overallStatus);
        step.put("criticalFailed", "FAILED".equals(overallStatus) || "ERROR".equals(overallStatus) || "BLOCKED".equals(overallStatus));
        step.put("durationMs", 10L);
        step.put("statusCode", statusCode);
        if (message != null) {
            step.put("message", message);
        }
        step.put("requestSnapshot", Map.of("stepId", stepId));
        step.put("responseSnapshot", statusCode == null ? Map.of() : Map.of("statusCode", statusCode));
        step.put("assertionResults", List.of());
        return step;
    }

    private void assertRecommendation(
        OverallStatus status,
        Integer statusCode,
        Map<String, Object> responseSnapshot,
        List<Map<String, Object>> assertionResults,
        boolean retryable,
        String retryReason,
        String nextSuggestion
    ) {
        var response = new java.util.LinkedHashMap<String, Object>(responseSnapshot);
        if (statusCode != null) {
            response.putIfAbsent("statusCode", statusCode);
        }
        var record = newExecutionRecord(status, Map.of("method", "GET", "path", "/api/orders"), response, assertionResults);
        record.setStatusCode(statusCode);
        if (response.containsKey("errorType")) {
            record.setErrorMessage(String.valueOf(response.get("errorType")));
        }
        record = executionRecords.save(record);
        entityManager.flush();
        entityManager.clear();

        var result = failureAnalysis.analyzeExecution(FailureAnalysisRequest.basic(record.getExecutionId()));

        assertThat(result.retryable()).isEqualTo(retryable);
        assertThat(result.retryReason()).contains(retryReason);
        assertThat(result.nextSuggestion()).contains(nextSuggestion);
        if (!result.observationIds().isEmpty()) {
            var observation = observations.findById(result.observationIds().getFirst()).orElseThrow();
            assertThat(observation.getNextSuggestion()).isEqualTo(result.nextSuggestion());
        }
    }
}
