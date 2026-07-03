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
class MemoryRefineryServiceTests {

    @Autowired
    private MemoryRefineryService memoryRefineryService;

    @Test
    void acceptsUsefulCandidateAndPersistsClassifiedLongTermMemory() {
        var result = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Missing tenant header causes 401 in order payment flow",
            "Order payment requests must include tenant context before auth and business assertions.",
            MemorySourceType.OBSERVATION,
            "observation-tenant-401",
            "task-phase4-03",
            List.of("payment", "auth"),
            0.89f,
            "Observed repeated 401 responses when POST /api/orders/{orderId}/pay omitted X-Tenant-Id.",
            Map.of("apiPath", "/api/orders/{orderId}/pay", "errorCode", "AUTH_401", "module", "payment")
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.created()).isTrue();
        assertThat(result.duplicateSuppressed()).isFalse();
        assertThat(result.rejectionReason()).isNull();
        assertThat(result.memory()).isNotNull();
        assertThat(result.memory().memoryId()).isNotBlank();
        assertThat(result.memory().scopeType()).isEqualTo(MemoryScopeType.FAILURE_PATTERN);
        assertThat(result.memory().summary()).contains("tenant header");
        assertThat(result.memory().content()).contains("tenant context");
        assertThat(result.memory().fullContent()).contains("POST /api/orders/{orderId}/pay");
        assertThat(result.memory().tags()).contains("auth", "auth_401", "failure_pattern", "payment");
        assertThat(result.memory().confidence()).isEqualTo(0.89f);
        assertThat(result.memory().importance()).isGreaterThan(0.72f);
        assertThat(result.memory().metadata())
            .containsEntry("taskId", "task-phase4-03")
            .containsEntry("scopeType", "FAILURE_PATTERN")
            .containsEntry("errorCode", "AUTH_401");
        assertThat(result.memory().embedding()).hasSize(1024);
    }

    @Test
    void rejectsLowConfidenceAndTaskLocalCandidatesWithClearReasons() {
        var lowConfidence = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Maybe useful retry note",
            "Possibly saw an odd retry, but confidence is weak.",
            MemorySourceType.EXECUTION_RESULT,
            "execution-low",
            "task-phase4-03-low",
            List.of("retry"),
            0.31f,
            "Temporary guess from a single run.",
            Map.of()
        ));
        var taskLocalOnly = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Keep this for this task only",
            "Do not reuse beyond the current task.",
            MemorySourceType.TASK_STATE,
            "task-local-note",
            "task-phase4-03-local",
            List.of(),
            0.78f,
            "This is task-local only and should not be refined.",
            Map.of()
        ));

        assertThat(lowConfidence.accepted()).isFalse();
        assertThat(lowConfidence.rejectionReason()).isEqualTo("low-confidence");
        assertThat(lowConfidence.memory()).isNull();

        assertThat(taskLocalOnly.accepted()).isFalse();
        assertThat(taskLocalOnly.rejectionReason()).isEqualTo("task-local-only");
        assertThat(taskLocalOnly.memory()).isNull();
    }

    @Test
    void classifiesPreferenceCandidatesAndKeepsRawEvidenceOutOfMainContent() {
        var result = memoryRefineryService.refine(new MemoryCandidateRequest(
            "User prefers terse failure summaries",
            "Summaries should stay compact and action-oriented for developers.",
            MemorySourceType.USER_FEEDBACK,
            "turn-14",
            null,
            List.of("ux"),
            0.83f,
            """
                User: please keep the reply short
                Assistant: acknowledged
                User: focus only on the concrete next step and blocker
                """,
            Map.of("module", "reporting")
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.memory().scopeType()).isEqualTo(MemoryScopeType.PREFERENCE);
        assertThat(result.memory().content().length()).isLessThan(result.memory().fullContent().length());
        assertThat(result.memory().fullContent()).contains("User: please keep the reply short");
        assertThat(result.memory().tags()).contains("preference", "reporting", "ux");
    }

    @Test
    void suppressesBasicDuplicateCandidates() {
        var request = new MemoryCandidateRequest(
            "Gateway timeout retry guidance",
            "Retry gateway timeouts with a smaller assertion window.",
            MemorySourceType.EXECUTION_RESULT,
            "execution-duplicate",
            "task-phase4-03-dup",
            List.of("retry", "payment"),
            0.87f,
            "Gateway timeout repeated across two execution attempts.",
            Map.of("errorCode", "GW_TIMEOUT")
        );

        var first = memoryRefineryService.refine(request);
        var second = memoryRefineryService.refine(new MemoryCandidateRequest(
            " Gateway timeout retry guidance ",
            " Retry gateway timeouts with a smaller assertion window. ",
            MemorySourceType.EXECUTION_RESULT,
            " execution-duplicate ",
            "task-phase4-03-dup",
            List.of("payment", "retry"),
            0.87f,
            "Gateway timeout repeated across two execution attempts.",
            Map.of("errorCode", "GW_TIMEOUT")
        ));

        assertThat(first.accepted()).isTrue();
        assertThat(first.created()).isTrue();
        assertThat(second.accepted()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.duplicateSuppressed()).isTrue();
        assertThat(second.memory().memoryId()).isEqualTo(first.memory().memoryId());
    }

    @Test
    void mergesStrongerEvidenceIntoExistingActiveMemory() {
        var first = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Gateway timeout requires shorter retry windows",
            "Retry gateway timeouts with a tighter window.",
            MemorySourceType.EXECUTION_RESULT,
            "execution-merge-1",
            "task-phase4-04",
            List.of("payment", "retry"),
            0.71f,
            "First execution showed a gateway timeout after three retries.",
            Map.of("errorCode", "GW_TIMEOUT", "module", "payment")
        ));
        var second = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Gateway timeout requires shorter retry windows",
            "Retry gateway timeouts with a tighter assertion and retry window.",
            MemorySourceType.EXECUTION_RESULT,
            "execution-merge-2",
            "task-phase4-04",
            List.of("retry", "payment", "timeout"),
            0.9f,
            "Second execution confirmed the same gateway timeout and showed the tighter retry window works better.",
            Map.of("errorCode", "GW_TIMEOUT", "module", "payment")
        ));

        assertThat(second.accepted()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.duplicateSuppressed()).isTrue();
        assertThat(second.memory().memoryId()).isEqualTo(first.memory().memoryId());
        assertThat(second.memory().confidence()).isEqualTo(0.9f);
        assertThat(second.memory().importance()).isGreaterThanOrEqualTo(first.memory().importance());
        assertThat(second.memory().fullContent())
            .contains("First execution showed a gateway timeout")
            .contains("Second execution confirmed the same gateway timeout");
        assertThat(second.memory().metadata())
            .containsEntry("mergeCount", 2)
            .containsEntry("evidenceCount", 2);
        assertThat((List<String>) second.memory().metadata().get("mergedSourceRefs"))
            .contains("execution-merge-1", "execution-merge-2");
    }

    @Test
    void createsSeparateMemoryForNonDuplicateCandidates() {
        var first = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Payment timeout retry guidance",
            "Retry payment gateway timeouts with a tighter window.",
            MemorySourceType.EXECUTION_RESULT,
            "execution-non-dup-1",
            "task-phase4-04-a",
            List.of("payment", "retry"),
            0.86f,
            "Timeout happened on payment flow.",
            Map.of("errorCode", "GW_TIMEOUT", "module", "payment")
        ));
        var second = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Order auth requires tenant bootstrap",
            "Order auth failures require tenant bootstrap before the request.",
            MemorySourceType.OBSERVATION,
            "execution-non-dup-2",
            "task-phase4-04-b",
            List.of("order", "auth"),
            0.88f,
            "Observed 401 on order flow without tenant bootstrap.",
            Map.of("errorCode", "AUTH_401", "module", "order")
        ));

        assertThat(second.created()).isTrue();
        assertThat(second.memory().memoryId()).isNotEqualTo(first.memory().memoryId());
    }

    @Test
    void doesNotReviveArchivedOrInactiveMemoriesSilently() {
        var archived = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Gateway timeout retry guidance",
            "Retry gateway timeouts with a smaller assertion window.",
            MemorySourceType.EXECUTION_RESULT,
            "execution-archived-1",
            "task-phase4-04-archived",
            List.of("payment", "retry"),
            0.83f,
            "Archived baseline evidence for gateway timeout.",
            Map.of("errorCode", "GW_TIMEOUT")
        ));
        memoryRefineryService.archiveMemory(archived.memory().memoryId());

        var archivedReplacement = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Gateway timeout retry guidance",
            "Retry gateway timeouts with a smaller assertion window.",
            MemorySourceType.EXECUTION_RESULT,
            "execution-archived-2",
            "task-phase4-04-archived",
            List.of("retry", "payment"),
            0.84f,
            "Fresh evidence should create a new active memory instead of reviving the archived one.",
            Map.of("errorCode", "GW_TIMEOUT")
        ));

        var inactive = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Order tenant bootstrap guidance",
            "Bootstrap tenant context before order auth checks.",
            MemorySourceType.OBSERVATION,
            "execution-inactive-1",
            "task-phase4-04-inactive",
            List.of("order", "tenant"),
            0.8f,
            "Inactive baseline evidence for tenant bootstrap.",
            Map.of("errorCode", "AUTH_401", "module", "order")
        ));
        memoryRefineryService.deactivateMemory(inactive.memory().memoryId());

        var inactiveReplacement = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Order tenant bootstrap guidance",
            "Bootstrap tenant context before order auth checks.",
            MemorySourceType.OBSERVATION,
            "execution-inactive-2",
            "task-phase4-04-inactive",
            List.of("tenant", "order"),
            0.86f,
            "Fresh evidence should create a new active memory instead of reviving the inactive one.",
            Map.of("errorCode", "AUTH_401", "module", "order")
        ));

        assertThat(archivedReplacement.created()).isTrue();
        assertThat(archivedReplacement.memory().memoryId()).isNotEqualTo(archived.memory().memoryId());
        assertThat(inactiveReplacement.created()).isTrue();
        assertThat(inactiveReplacement.memory().memoryId()).isNotEqualTo(inactive.memory().memoryId());
    }
}
