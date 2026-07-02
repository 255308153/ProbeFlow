package com.probeflow.testagent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.memory.LongTermMemory;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.memory.MemoryStatus;
import com.probeflow.testagent.memory.MemoryType;
import com.probeflow.testagent.memory.SessionMemoryItem;
import com.probeflow.testagent.memory.TaskMemoryItem;
import com.probeflow.testagent.memory.TaskMemoryItemRepository;
import jakarta.persistence.EntityManager;
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
class MemoryItemRepositoryTests {

    @Autowired
    private TaskMemoryItemRepository taskMemories;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private EntityManager entityManager;

    @Test
    void taskAndLongTermMemoryCanBePersistedWithScopedMetadataAndVectorEmbedding() {
        var sessionMemory = new SessionMemoryItem();
        sessionMemory.setSessionId("session-issue-07");
        sessionMemory.setScopeType(MemoryScopeType.PREFERENCE);
        sessionMemory.setSummary("Reviewer prefers compact reports");
        sessionMemory.setContent("Keep generated report summaries focused on failing API cases.");
        sessionMemory.setTags(List.of("report", "preference"));
        sessionMemory.setSourceType(MemorySourceType.USER_FEEDBACK);
        sessionMemory.setSourceRef("turn-17");
        sessionMemory.setConfidence(0.74f);
        sessionMemory.setStatus(MemoryStatus.ACTIVE);
        sessionMemory.setExpiresAt(Instant.parse("2026-07-03T00:00:00Z"));

        assertThat(sessionMemory.getMemoryType()).isEqualTo(MemoryType.SESSION);

        var taskMemory = new TaskMemoryItem();
        taskMemory.setTaskId("task-issue-07");
        taskMemory.setScopeType(MemoryScopeType.FAILURE_PATTERN);
        taskMemory.setSummary("Order API rejected missing tenant header");
        taskMemory.setContent("The last execution returned 401 when X-Tenant-Id was omitted.");
        taskMemory.setTags(List.of("order", "auth"));
        taskMemory.setSourceType(MemorySourceType.OBSERVATION);
        taskMemory.setSourceRef("observation-401");
        taskMemory.setConfidence(0.91f);
        taskMemory.setStatus(MemoryStatus.ACTIVE);
        taskMemory.setLifecycleStage("running");
        taskMemory.setMetadata(Map.of("httpStatus", 401));
        var savedTaskMemory = taskMemories.save(taskMemory);

        var longTermMemory = new LongTermMemory();
        longTermMemory.setScopeType(MemoryScopeType.TESTING_PATTERN);
        longTermMemory.setSummary("Tenant auth is required before order assertions");
        longTermMemory.setContent("Order API tests should create or inject tenant context before business assertions.");
        longTermMemory.setFullContent("Repeated order API failures showed that tenant setup must happen before stock checks.");
        longTermMemory.setTags(List.of("order", "tenant", "auth"));
        longTermMemory.setSourceType(MemorySourceType.MEMORY_REFINERY);
        longTermMemory.setSourceRef("task-issue-07");
        longTermMemory.setConfidence(0.88f);
        longTermMemory.setImportance(0.82f);
        longTermMemory.setHitCount(3);
        longTermMemory.setSuccessContribution(0.67f);
        longTermMemory.setStatus(MemoryStatus.ACTIVE);
        longTermMemory.setLastUsedAt(Instant.parse("2026-07-02T12:30:00Z"));
        longTermMemory.setMetadata(Map.of("module", "order"));
        longTermMemory.setEmbedding(testEmbedding());
        var savedLongTermMemory = longTermMemories.save(longTermMemory);

        entityManager.flush();
        entityManager.clear();

        var loadedTaskMemory = taskMemories.findById(savedTaskMemory.getMemoryId()).orElseThrow();
        var loadedLongTermMemory = longTermMemories.findById(savedLongTermMemory.getMemoryId()).orElseThrow();

        assertThat(loadedTaskMemory.getMemoryType()).isEqualTo(MemoryType.TASK);
        assertThat(loadedTaskMemory.getTaskId()).isEqualTo("task-issue-07");
        assertThat(loadedTaskMemory.getScopeType()).isEqualTo(MemoryScopeType.FAILURE_PATTERN);
        assertThat(loadedTaskMemory.getTags()).containsExactly("order", "auth");
        assertThat(loadedTaskMemory.getConfidence()).isEqualTo(0.91f);
        assertThat(loadedTaskMemory.getMetadata()).containsEntry("httpStatus", 401);

        assertThat(loadedLongTermMemory.getMemoryType()).isEqualTo(MemoryType.LONG_TERM);
        assertThat(loadedLongTermMemory.getScopeType()).isEqualTo(MemoryScopeType.TESTING_PATTERN);
        assertThat(loadedLongTermMemory.getTags()).containsExactly("order", "tenant", "auth");
        assertThat(loadedLongTermMemory.getImportance()).isEqualTo(0.82f);
        assertThat(loadedLongTermMemory.getHitCount()).isEqualTo(3);
        assertThat(loadedLongTermMemory.getEmbedding()).hasSize(1024);
        assertThat(loadedLongTermMemory.getEmbedding()[0]).isEqualTo(0.002f);
        assertThat(loadedLongTermMemory.getEmbedding()[1023]).isEqualTo(2.048f);
    }

    private float[] testEmbedding() {
        var embedding = new float[1024];
        for (int index = 0; index < embedding.length; index++) {
            embedding[index] = (index + 1) / 500.0f;
        }
        return embedding;
    }
}
