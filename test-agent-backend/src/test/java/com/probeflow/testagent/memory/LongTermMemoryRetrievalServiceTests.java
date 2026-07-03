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
class LongTermMemoryRetrievalServiceTests {

    @Autowired
    private MemoryRefineryService memoryRefineryService;

    @Autowired
    private LongTermMemoryRetrievalService retrievalService;

    @Test
    void filtersByStructureAndExcludesInactiveArchivedMemories() {
        var active = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Payment timeout needs retry guidance",
            "Retry payment gateway timeouts with a tighter window.",
            MemorySourceType.EXECUTION_RESULT,
            "retrieval-active",
            "task-phase4-05-a",
            List.of("payment", "retry", "timeout"),
            0.87f,
            "Observed gateway timeout on payment flow.",
            Map.of("systemName", "order-platform", "module", "payment", "apiPath", "/api/pay", "errorCode", "GW_TIMEOUT")
        ));
        var archived = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Prefer legacy payment timeout wording in reports",
            "Preference note for archived report phrasing.",
            MemorySourceType.USER_FEEDBACK,
            "retrieval-archived",
            "task-phase4-05-b",
            List.of("payment", "preference"),
            0.8f,
            "Archived user preference evidence.",
            Map.of("systemName", "order-platform", "module", "payment", "apiPath", "/api/pay", "errorCode", "GW_TIMEOUT")
        ));
        memoryRefineryService.archiveMemory(archived.memory().memoryId());

        var inactive = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Prefer compact timeout footnotes for payment summaries",
            "Preference note for inactive summary formatting.",
            MemorySourceType.USER_FEEDBACK,
            "retrieval-inactive",
            "task-phase4-05-c",
            List.of("payment", "compact"),
            0.81f,
            "Inactive user preference evidence.",
            Map.of("systemName", "order-platform", "module", "payment", "apiPath", "/api/pay", "errorCode", "GW_TIMEOUT")
        ));
        memoryRefineryService.deactivateMemory(inactive.memory().memoryId());

        var result = retrievalService.retrieve(new LongTermMemoryQuery(
            "execution_preparation",
            "payment timeout retry guidance",
            "order-platform",
            "payment",
            "/api/pay",
            "GW_TIMEOUT",
            List.of("payment", "timeout"),
            List.of(),
            5,
            200
        ));

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().getFirst().memoryId()).isEqualTo(active.memory().memoryId());
    }

    @Test
    void appliesStageAwareOrderingBetweenFailureAndTestingPatterns() {
        memoryRefineryService.refine(new MemoryCandidateRequest(
            "Gateway timeout failure pattern",
            "Gateway timeout failures require narrower retry windows.",
            MemorySourceType.EXECUTION_RESULT,
            "stage-failure",
            "task-phase4-05-stage-1",
            List.of("payment", "timeout", "retry"),
            0.89f,
            "Failure analysis evidence for gateway timeouts.",
            Map.of("module", "payment", "errorCode", "GW_TIMEOUT")
        ));
        memoryRefineryService.refine(new MemoryCandidateRequest(
            "Payment setup checklist",
            "Test setup should provision tenant and auth fixtures before execution.",
            MemorySourceType.MANUAL,
            "stage-testing",
            "task-phase4-05-stage-2",
            List.of("payment", "checklist", "test"),
            0.84f,
            "Testing pattern evidence for payment setup and assertions.",
            Map.of("module", "payment")
        ));

        var failureResult = retrievalService.retrieve(new LongTermMemoryQuery(
            "failure_analysis",
            "payment timeout retry",
            null,
            "payment",
            null,
            null,
            List.of("payment", "retry"),
            List.of(),
            5,
            200
        ));
        var generationResult = retrievalService.retrieve(new LongTermMemoryQuery(
            "case_generation",
            "payment setup checklist",
            null,
            "payment",
            null,
            null,
            List.of("payment", "test"),
            List.of(),
            5,
            200
        ));

        assertThat(failureResult.hits().getFirst().scopeType()).isEqualTo(MemoryScopeType.FAILURE_PATTERN);
        assertThat(generationResult.hits().getFirst().scopeType()).isEqualTo(MemoryScopeType.TESTING_PATTERN);
    }

    @Test
    void usesVectorSimilarityAndUpdatesUsageTrackingWhenSelected() {
        var refined = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Tenant bootstrap prevents order auth failures",
            "Bootstrap tenant context before order auth checks to avoid 401 responses.",
            MemorySourceType.OBSERVATION,
            "vector-hit",
            "task-phase4-05-vector",
            List.of("order", "tenant", "auth"),
            0.91f,
            "Observed order auth failures disappear after tenant bootstrap.",
            Map.of("module", "order", "errorCode", "AUTH_401")
        ));

        var result = retrievalService.retrieve(new LongTermMemoryQuery(
            "execution_preparation",
            "avoid order auth 401 by bootstrapping tenant context",
            null,
            "order",
            null,
            "AUTH_401",
            List.of("order", "tenant"),
            List.of(MemoryScopeType.FAILURE_PATTERN),
            3,
            200
        ));

        assertThat(result.hits()).hasSize(1);
        var hit = result.hits().getFirst();
        assertThat(hit.memoryId()).isEqualTo(refined.memory().memoryId());
        assertThat(hit.score()).isGreaterThan(0.45d);
        assertThat(hit.matchReasons()).contains("semantic-match");
        assertThat(hit.hitCount()).isEqualTo(1);
        assertThat(hit.lastUsedAt()).isNotNull();
        assertThat(Instant.parse(hit.metadata().get("selectedAt").toString())).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void honorsLimitAndTokenBudgetHints() {
        memoryRefineryService.refine(new MemoryCandidateRequest(
            "Payment retry checklist",
            "Checklist: provision auth, tenant, retry window, and timeout assertions before execution.",
            MemorySourceType.MANUAL,
            "budget-1",
            "task-phase4-05-budget-1",
            List.of("payment", "checklist"),
            0.83f,
            "Budget candidate one.",
            Map.of("module", "payment")
        ));
        memoryRefineryService.refine(new MemoryCandidateRequest(
            "Payment gateway failure note",
            "Gateway timeout failures usually require smaller retry windows and clearer assertions.",
            MemorySourceType.EXECUTION_RESULT,
            "budget-2",
            "task-phase4-05-budget-2",
            List.of("payment", "timeout"),
            0.88f,
            "Budget candidate two.",
            Map.of("module", "payment", "errorCode", "GW_TIMEOUT")
        ));

        var result = retrievalService.retrieve(new LongTermMemoryQuery(
            "case_generation",
            "payment checklist and timeout guidance",
            null,
            "payment",
            null,
            null,
            List.of("payment"),
            List.of(),
            1,
            12
        ));

        assertThat(result.hits()).hasSize(1);
        assertThat(result.totalTokens()).isLessThanOrEqualTo(12);
    }
}
