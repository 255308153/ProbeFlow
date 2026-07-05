package com.probeflow.testagent.failureanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.probeflow.testagent.executionrecord.ExecutionRecord;
import com.probeflow.testagent.executionrecord.ExecutionRecordRepository;
import com.probeflow.testagent.executionrecord.ExecutorType;
import com.probeflow.testagent.executionrecord.OverallStatus;
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
}
