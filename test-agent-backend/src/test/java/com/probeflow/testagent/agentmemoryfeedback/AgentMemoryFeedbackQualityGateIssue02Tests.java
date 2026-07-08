package com.probeflow.testagent.agentmemoryfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.memory.MemoryCandidateRequest;
import com.probeflow.testagent.memory.MemorySourceType;
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
class AgentMemoryFeedbackQualityGateIssue02Tests {

    @Autowired
    private AgentMemoryFeedbackApplicationService memoryFeedback;

    @Autowired
    private MemoryCandidateRecordRepository candidates;

    @Autowired
    private TaskRepository tasks;

    @Test
    void refineryQualityRejectionIsVisibleInCandidateAuditSummary() {
        var task = tasks.save(newTask("issue02-agent-quality-task"));

        var result = memoryFeedback.refineFailureAnalysisCandidate(
            AgentMemoryCandidateSourceType.FAILURE_ANALYSIS,
            new MemoryCandidateRequest(
                "Temporary payment gateway note",
                "Temporary one-off payment timeout from a single run.",
                MemorySourceType.EXECUTION_RESULT,
                "issue02-agent-one-off",
                task.getTaskId(),
                List.of("payment", "timeout"),
                0.82f,
                "Single run evidence should be audited but rejected by quality gate.",
                Map.of("module", "payment")
            )
        );

        assertThat(result.status()).isEqualTo(MemoryCandidateProcessingStatus.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo("one-off-noise");
        assertThat(result.auditSummary())
            .containsEntry("writesLongTermMemory", false)
            .containsEntry("refineryInvoked", true)
            .containsEntry("rejectionReason", "one-off-noise");
        var record = candidates.findById(result.candidateId()).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(MemoryCandidateProcessingStatus.REJECTED);
        assertThat(record.getRefineryResultSummary())
            .containsEntry("accepted", false)
            .containsEntry("rejectionReason", "one-off-noise");
    }

    private Task newTask(String taskId) {
        var task = new Task();
        task.setTaskId(taskId);
        task.setTaskType(TaskType.API_TEST);
        task.setTaskName("Issue 02 memory quality task");
        task.setStatus(TaskStatus.ANALYZING_RESULTS);
        task.setSourceType(TaskSourceType.MANUAL);
        task.setSourceRef("issue02");
        task.setTargetApiSpecIds(List.of());
        task.setPromotionMode(PromotionMode.MANUAL);
        task.setPriority(TaskPriority.MEDIUM);
        task.setCreator("v5-2-test");
        task.setMetadata(Map.of());
        return task;
    }
}
