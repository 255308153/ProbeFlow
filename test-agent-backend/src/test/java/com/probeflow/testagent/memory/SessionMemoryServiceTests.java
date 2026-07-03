package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

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
class SessionMemoryServiceTests {

    @Autowired
    private SessionMemoryService sessionMemoryService;

    @Test
    void writesAndReadsActiveSessionContextInDeterministicOrder() {
        var first = sessionMemoryService.writeSessionMemory(new SessionMemoryWriteRequest(
            "session-phase4-02",
            MemoryScopeType.PREFERENCE,
            " Keep failure summaries compact ",
            " Focus only on the latest blocking error and next retry decision. ",
            List.of("preference", "report"),
            MemorySourceType.USER_FEEDBACK,
            " turn-7 ",
            0.82f,
            Map.of("audience", "developer"),
            600L
        ));
        var second = sessionMemoryService.writeSessionMemory(new SessionMemoryWriteRequest(
            "session-phase4-02",
            MemoryScopeType.FAILURE_PATTERN,
            "Recent retry stalled on gateway timeout",
            "Retry state should preserve the timeout reason for the next step.",
            List.of("retry", "payment"),
            MemorySourceType.EXECUTION_RESULT,
            "execution-55",
            0.9f,
            Map.of("errorCode", "GW_TIMEOUT"),
            900L
        ));

        var result = sessionMemoryService.readActiveSessionMemories("session-phase4-02");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(SessionMemoryView::memoryId)
            .containsExactly(first.memoryId(), second.memoryId());
        assertThat(result.getFirst().summary()).isEqualTo("Keep failure summaries compact");
        assertThat(result.getFirst().content()).isEqualTo("Focus only on the latest blocking error and next retry decision.");
        assertThat(result.getFirst().tags()).containsExactly("preference", "report");
        assertThat(result.getFirst().ttlSeconds()).isEqualTo(600L);
        assertThat(result.getFirst().expiresAt()).isAfter(result.getFirst().createdAt());
    }

    @Test
    void excludesExpiredAndInactiveSessionMemoryByDefault() {
        sessionMemoryService.writeSessionMemory(new SessionMemoryWriteRequest(
            "session-phase4-status",
            MemoryScopeType.PREFERENCE,
            "Expired short-term note",
            "This item should already be expired.",
            List.of("expired"),
            MemorySourceType.USER_FEEDBACK,
            "turn-expired",
            0.61f,
            Map.of(),
            1L
        ));
        var inactive = sessionMemoryService.writeSessionMemory(new SessionMemoryWriteRequest(
            "session-phase4-status",
            MemoryScopeType.FAILURE_PATTERN,
            "Inactive retry note",
            "This item will be deactivated.",
            List.of("retry"),
            MemorySourceType.EXECUTION_RESULT,
            "execution-inactive",
            0.79f,
            Map.of(),
            300L
        ));
        var active = sessionMemoryService.writeSessionMemory(new SessionMemoryWriteRequest(
            "session-phase4-status",
            MemoryScopeType.PROJECT_KNOWLEDGE,
            "Active goal note",
            "Remember the current payment debugging goal.",
            List.of("goal"),
            MemorySourceType.TASK_STATE,
            "task-phase4-02",
            0.92f,
            Map.of("module", "payment"),
            300L
        ));

        sessionMemoryService.deactivateSessionMemory(inactive.memoryId());
        try {
            Thread.sleep(1100L);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for session TTL expiry", interruptedException);
        }

        var result = sessionMemoryService.readActiveSessionMemories("session-phase4-status");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().memoryId()).isEqualTo(active.memoryId());
        assertThat(result.getFirst().summary()).isEqualTo("Active goal note");
    }

    @Test
    void supportsCompactSessionContextWithoutTranscriptSpecificFields() {
        sessionMemoryService.writeSessionMemory(new SessionMemoryWriteRequest(
            "session-phase4-compact",
            MemoryScopeType.PREFERENCE,
            "User wants terse next steps",
            "Keep the next reply action-oriented.",
            List.of("ux"),
            MemorySourceType.USER_FEEDBACK,
            null,
            0.88f,
            Map.of("turnKind", "feedback"),
            null
        ));

        var result = sessionMemoryService.readActiveSessionMemories("session-phase4-compact");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().summary()).doesNotContain("User:", "Assistant:");
        assertThat(result.getFirst().content()).isEqualTo("Keep the next reply action-oriented.");
        assertThat(result.getFirst().ttlSeconds()).isEqualTo(86_400L);
    }
}
