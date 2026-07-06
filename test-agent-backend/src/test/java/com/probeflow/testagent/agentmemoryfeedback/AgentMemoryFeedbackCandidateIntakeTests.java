package com.probeflow.testagent.agentmemoryfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.memory.LongTermMemoryRepository;
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
class AgentMemoryFeedbackCandidateIntakeTests {

    @Autowired
    private AgentMemoryFeedbackApplicationService memoryFeedback;

    @Autowired
    private MemoryCandidateRecordRepository candidates;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private EntityManager entityManager;

    @Test
    void acceptsCandidateIntoPendingAuditRecordWithoutRefineryOrLongTermMemoryWrites() {
        saveTask("task-memory-feedback-01", TaskStatus.EXECUTING);

        var result = memoryFeedback.submitCandidate(new AgentMemoryCandidateIntakeRequest(
            AgentMemoryCandidateSourceType.SYSTEM,
            "system:pattern-01",
            "task-memory-feedback-01",
            "Repeated staging setup requires tenant bootstrap",
            "Before running payment tests, bootstrap tenant context and auth fixtures.",
            "Operator note says tenant bootstrap fixed repeated staging failures.",
            List.of("Payment", "tenant", "payment"),
            0.78f,
            Map.of("module", "payment", "apiPath", "/api/orders/{orderId}/pay")
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(result.status()).isEqualTo(MemoryCandidateProcessingStatus.PENDING);
        assertThat(result.candidateId()).isNotBlank();
        assertThat(result.auditSummary())
            .containsEntry("sourceType", AgentMemoryCandidateSourceType.SYSTEM.name())
            .containsEntry("rawEvidenceIncluded", false)
            .containsEntry("writesLongTermMemory", false)
            .containsEntry("refineryInvoked", false);

        var record = candidates.findById(result.candidateId()).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(MemoryCandidateProcessingStatus.PENDING);
        assertThat(record.getTags()).containsExactly("payment", "tenant");
        assertThat(record.getRefineryResultSummary()).containsEntry("refineryInvoked", false);
        assertThat(record.getMemoryId()).isNull();
        assertThat(longTermMemories.findAll()).isEmpty();

        var savedTask = tasks.findById("task-memory-feedback-01").orElseThrow();
        assertThat(metadataRecords(savedTask.getMetadata().get("agentMemoryFeedbackCandidates")))
            .singleElement()
            .satisfies(handoff -> {
                assertThat(handoff).containsEntry("candidateId", result.candidateId());
                assertThat(handoff).containsEntry("sourceRef", "system:pattern-01");
                assertThat(handoff).containsEntry("writesLongTermMemory", false);
            });
    }

    @Test
    void duplicateSourceTypeRefAndTaskReturnsIdempotentDuplicateWithoutNewRecord() {
        saveTask("task-memory-feedback-dup", TaskStatus.EXECUTING);
        var request = candidateRequest("task-memory-feedback-dup", "manual:duplicate");

        var first = memoryFeedback.submitCandidate(request);
        var second = memoryFeedback.submitCandidate(request);

        assertThat(first.status()).isEqualTo(MemoryCandidateProcessingStatus.PENDING);
        assertThat(second.status()).isEqualTo(MemoryCandidateProcessingStatus.DUPLICATE);
        assertThat(second.candidateId()).isEqualTo(first.candidateId());
        assertThat(second.auditSummary()).containsEntry("idempotent", true);
        assertThat(candidates.findAllByTaskIdOrderByCreatedAtAscCandidateIdAsc("task-memory-feedback-dup"))
            .hasSize(1);
    }

    @Test
    void rejectsInvalidCandidateWithoutAuditHalfWrite() {
        saveTask("task-memory-feedback-invalid", TaskStatus.EXECUTING);

        var result = memoryFeedback.submitCandidate(new AgentMemoryCandidateIntakeRequest(
            null,
            " ",
            "task-memory-feedback-invalid",
            "",
            "",
            "",
            List.of(),
            1.4f,
            Map.of()
        ));

        assertThat(result.status()).isEqualTo(MemoryCandidateProcessingStatus.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo("invalid-candidate");
        assertThat(result.blockers())
            .contains("sourceType is required")
            .contains("sourceRef is required")
            .contains("summary, content or rawEvidence is required")
            .contains("confidence must be between 0.0 and 1.0");
        assertThat(candidates.findAllByTaskIdOrderByCreatedAtAscCandidateIdAsc("task-memory-feedback-invalid"))
            .isEmpty();
    }

    @Test
    void masksSensitiveFieldsInCandidateContentEvidenceMetadataAndAuditSummary() {
        saveTask("task-memory-feedback-mask", TaskStatus.EXECUTING);

        var result = memoryFeedback.submitCandidate(new AgentMemoryCandidateIntakeRequest(
            AgentMemoryCandidateSourceType.MANUAL,
            "manual:secret-note",
            "task-memory-feedback-mask",
            "Auth setup note with token=secret-summary-token",
            "Use Authorization: Bearer secret-content-token and password=plain-password before retry.",
            "cookie=session-secret; apiKey=plain-api-key; token=secret-evidence-token",
            List.of("auth"),
            0.82f,
            Map.of(
                "token", "secret-metadata-token",
                "nested", Map.of("authorization", "Bearer secret-nested-token"),
                "safe", "retry auth setup"
            )
        ));

        entityManager.flush();
        entityManager.clear();

        var record = candidates.findById(result.candidateId()).orElseThrow();
        assertThat(record.getSummary()).doesNotContain("secret-summary-token");
        assertThat(record.getContent()).doesNotContain("secret-content-token").doesNotContain("plain-password");
        assertThat(record.getSanitizedEvidence()).doesNotContain("plain-api-key").doesNotContain("secret-evidence-token");
        assertThat(record.getMetadata().toString()).doesNotContain("secret-metadata-token").doesNotContain("secret-nested-token");
        assertThat(record.getAuditSummary().toString()).doesNotContain("secret-summary-token");
        assertThat(record.getSummary()).contains(MemoryFeedbackSanitizer.MASKED_VALUE);
        assertThat(record.getMetadata().toString()).contains(MemoryFeedbackSanitizer.MASKED_VALUE);
    }

    @Test
    void rejectsCompletedOrCancelledTaskCandidatesWithoutHalfWrite() {
        saveTask("task-memory-feedback-completed", TaskStatus.COMPLETED);
        saveTask("task-memory-feedback-cancelled", TaskStatus.CANCELLED);

        var completed = memoryFeedback.submitCandidate(candidateRequest("task-memory-feedback-completed", "manual:completed"));
        var cancelled = memoryFeedback.submitCandidate(candidateRequest("task-memory-feedback-cancelled", "manual:cancelled"));

        assertThat(completed.status()).isEqualTo(MemoryCandidateProcessingStatus.REJECTED);
        assertThat(completed.rejectionReason()).isEqualTo("terminal-task");
        assertThat(cancelled.status()).isEqualTo(MemoryCandidateProcessingStatus.REJECTED);
        assertThat(cancelled.rejectionReason()).isEqualTo("terminal-task");
        assertThat(candidates.findAllByTaskIdOrderByCreatedAtAscCandidateIdAsc("task-memory-feedback-completed")).isEmpty();
        assertThat(candidates.findAllByTaskIdOrderByCreatedAtAscCandidateIdAsc("task-memory-feedback-cancelled")).isEmpty();
        assertThat(tasks.findById("task-memory-feedback-completed").orElseThrow().getMetadata())
            .doesNotContainKey("agentMemoryFeedbackCandidates");
        assertThat(tasks.findById("task-memory-feedback-cancelled").orElseThrow().getMetadata())
            .doesNotContainKey("agentMemoryFeedbackCandidates");
    }

    private AgentMemoryCandidateIntakeRequest candidateRequest(String taskId, String sourceRef) {
        return new AgentMemoryCandidateIntakeRequest(
            AgentMemoryCandidateSourceType.MANUAL,
            sourceRef,
            taskId,
            "Manual memory feedback candidate",
            "Remember that staging auth needs tenant bootstrap before execution.",
            "Manual evidence from reviewer.",
            List.of("auth", "staging"),
            0.76f,
            Map.of("module", "order")
        );
    }

    private Task saveTask(String taskId, TaskStatus status) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Agent memory feedback " + taskId);
        task.setStatus(status);
        task.setSourceType(TaskSourceType.OPENAPI);
        task.setSourceRef("source-" + taskId);
        task.setTargetApiSpecIds(List.of("api-" + taskId));
        task.setPromotionMode(PromotionMode.AUTO);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("phase7");
        task.setMetadata(Map.of());
        return tasks.save(task);
    }

    private List<Map<String, Object>> metadataRecords(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .map(this::metadataMap)
            .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataMap(Object value) {
        return (Map<String, Object>) value;
    }
}
