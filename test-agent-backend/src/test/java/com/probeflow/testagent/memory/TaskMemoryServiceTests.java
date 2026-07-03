package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
class TaskMemoryServiceTests {

    @Autowired
    private TaskMemoryService taskMemoryService;

    @Test
    void writesAndReadsActiveTaskFactsInDeterministicOrder() {
        var firstWrite = taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            "task-phase4-01",
            MemoryScopeType.FAILURE_PATTERN,
            " Missing tenant header caused 401 ",
            " Execution failed because X-Tenant-Id was omitted. ",
            List.of("Auth", "order"),
            MemorySourceType.OBSERVATION,
            " observation-401 ",
            0.91f,
            " Running ",
            Map.of("httpStatus", 401, " apiPath ", "/api/orders/{orderId}"),
            Instant.now().plusSeconds(3600)
        ));
        var secondWrite = taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            "task-phase4-01",
            MemoryScopeType.PROJECT_KNOWLEDGE,
            "Order API requires tenant context",
            "Tenant context must be resolved before order assertions run.",
            List.of("order", "tenant"),
            MemorySourceType.TASK_STATE,
            "task-phase4-01",
            0.84f,
            "analysis",
            Map.of("module", "order"),
            null
        ));

        var result = taskMemoryService.readActiveTaskMemories("task-phase4-01");

        assertThat(firstWrite.created()).isTrue();
        assertThat(secondWrite.created()).isTrue();
        assertThat(result).hasSize(2);
        assertThat(result).extracting(TaskMemoryView::memoryId)
            .containsExactly(firstWrite.memoryId(), secondWrite.memoryId());
        assertThat(result.getFirst().summary()).isEqualTo("Missing tenant header caused 401");
        assertThat(result.getFirst().content()).isEqualTo("Execution failed because X-Tenant-Id was omitted.");
        assertThat(result.getFirst().tags()).containsExactly("auth", "order");
        assertThat(result.getFirst().lifecycleStage()).isEqualTo("running");
        assertThat(result.getFirst().metadata())
            .containsEntry("apiPath", "/api/orders/{orderId}")
            .containsEntry("httpStatus", 401);
    }

    @Test
    void canFilterTaskFactsByLifecycleStage() {
        taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            "task-phase4-stage",
            MemoryScopeType.PROJECT_KNOWLEDGE,
            "Analysis fact",
            "Discovered auth prerequisite during analysis.",
            List.of("auth"),
            MemorySourceType.TASK_STATE,
            "task-phase4-stage",
            0.77f,
            "analysis",
            Map.of("stage", "analysis"),
            null
        ));
        taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            "task-phase4-stage",
            MemoryScopeType.FAILURE_PATTERN,
            "Execution fact",
            "Observed a retryable 503 during execution.",
            List.of("execution", "retry"),
            MemorySourceType.EXECUTION_RESULT,
            "execution-503",
            0.81f,
            "execution",
            Map.of("httpStatus", 503),
            null
        ));

        var analysisFacts = taskMemoryService.readActiveTaskMemories("task-phase4-stage", "analysis");

        assertThat(analysisFacts).hasSize(1);
        assertThat(analysisFacts.getFirst().summary()).isEqualTo("Analysis fact");
        assertThat(analysisFacts.getFirst().lifecycleStage()).isEqualTo("analysis");
    }

    @Test
    void excludesExpiredInactiveAndArchivedFactsFromDefaultReads() {
        taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            "task-phase4-status",
            MemoryScopeType.PROJECT_KNOWLEDGE,
            "Expired fact",
            "This should not be returned because it already expired.",
            List.of("expired"),
            MemorySourceType.MANUAL,
            "manual-expired",
            0.66f,
            "analysis",
            Map.of(),
            Instant.now().minusSeconds(30)
        ));
        var inactive = taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            "task-phase4-status",
            MemoryScopeType.PREFERENCE,
            "Inactive fact",
            "This should be hidden after deactivation.",
            List.of("inactive"),
            MemorySourceType.USER_FEEDBACK,
            "turn-11",
            0.73f,
            "analysis",
            Map.of(),
            null
        ));
        var archived = taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            "task-phase4-status",
            MemoryScopeType.FAILURE_PATTERN,
            "Archived fact",
            "This should be hidden after archival.",
            List.of("archived"),
            MemorySourceType.OBSERVATION,
            "observation-7",
            0.88f,
            "execution",
            Map.of(),
            null
        ));
        var active = taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            "task-phase4-status",
            MemoryScopeType.PROJECT_KNOWLEDGE,
            "Active fact",
            "This is the only fact that should remain visible.",
            List.of("active"),
            MemorySourceType.MANUAL,
            "manual-active",
            0.95f,
            "analysis",
            Map.of("module", "payment"),
            Instant.now().plusSeconds(3600)
        ));

        taskMemoryService.deactivateTaskMemory(inactive.memoryId());
        taskMemoryService.archiveTaskMemory(archived.memoryId());

        var result = taskMemoryService.readActiveTaskMemories("task-phase4-status");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().memoryId()).isEqualTo(active.memoryId());
        assertThat(result.getFirst().summary()).isEqualTo("Active fact");
    }

    @Test
    void suppressesRepeatedEquivalentWritesAndReturnsStableIdentifier() {
        var request = new TaskMemoryWriteRequest(
            "task-phase4-dedupe",
            MemoryScopeType.FAILURE_PATTERN,
            "Retry window exceeded",
            "Gateway retries exceeded the configured window.",
            List.of("payment", "retry"),
            MemorySourceType.EXECUTION_RESULT,
            "execution-42",
            0.86f,
            "execution",
            Map.of("errorCode", "GW_TIMEOUT"),
            Instant.now().plusSeconds(7200)
        );

        var first = taskMemoryService.writeTaskMemory(request);
        var second = taskMemoryService.writeTaskMemory(new TaskMemoryWriteRequest(
            "task-phase4-dedupe",
            MemoryScopeType.FAILURE_PATTERN,
            " Retry window exceeded ",
            " Gateway retries exceeded the configured window. ",
            List.of("retry", "payment"),
            MemorySourceType.EXECUTION_RESULT,
            " execution-42 ",
            0.86f,
            " Execution ",
            Map.of("errorCode", "GW_TIMEOUT"),
            request.expiresAt()
        ));
        var result = taskMemoryService.readActiveTaskMemories("task-phase4-dedupe");

        assertThat(first.created()).isTrue();
        assertThat(first.duplicateSuppressed()).isFalse();
        assertThat(second.created()).isFalse();
        assertThat(second.duplicateSuppressed()).isTrue();
        assertThat(second.memoryId()).isEqualTo(first.memoryId());
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().memoryId()).isEqualTo(first.memoryId());
    }
}
