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
class MemoryFactIdentityConflictGuardIssue04Tests {

    @Autowired
    private MemoryRefineryService memoryRefineryService;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Test
    void rejectsComparableCandidatesWhenAnyCriticalIdentityHintConflicts() {
        var cases = List.of(
            new ConflictCase("systemName", "billing", "warehouse"),
            new ConflictCase("module", "payment", "refund"),
            new ConflictCase("apiPath", "/api/payments/charge", "/api/refunds/create"),
            new ConflictCase("httpMethod", "POST", "GET"),
            new ConflictCase("errorCode", "GW_TIMEOUT", "AUTH_401"),
            new ConflictCase("businessEntity", "payment", "refund"),
            new ConflictCase("failureClassification", "gateway-timeout", "auth-missing")
        );

        for (var conflictCase : cases) {
            longTermMemories.deleteAll();
            var first = memoryRefineryService.refine(conflictCandidate(
                "issue04-" + conflictCase.field() + "-a",
                "issue04-task-" + conflictCase.field() + "-a",
                conflictCase.field(),
                conflictCase.existingValue()
            ));
            var memoryCountBeforeConflict = longTermMemories.count();

            var rejected = memoryRefineryService.refine(conflictCandidate(
                "issue04-" + conflictCase.field() + "-b",
                "issue04-task-" + conflictCase.field() + "-b",
                conflictCase.field(),
                conflictCase.candidateValue()
            ));

            assertThat(first.created()).isTrue();
            assertThat(rejected.accepted()).isFalse();
            assertThat(rejected.rejectionReason()).isEqualTo("identity-conflict");
            assertThat(rejected.memory()).isNull();
            assertThat(longTermMemories.count()).isEqualTo(memoryCountBeforeConflict);
            assertThat(rejected.auditSummary())
                .containsEntry("reason", "identity-conflict")
                .containsEntry("conflictCandidate", true)
                .containsEntry("existingMemoryId", first.memory().memoryId());
            assertThat((List<String>) rejected.auditSummary().get("conflictFields"))
                .contains(conflictCase.field());
            assertThat((List<Map<String, Object>>) rejected.auditSummary().get("identityConflicts"))
                .anySatisfy(conflict -> assertThat(conflict)
                    .containsEntry("field", conflictCase.field())
                    .containsEntry("existingValue", conflictCase.existingValue())
                    .containsEntry("candidateValue", conflictCase.candidateValue()));
            assertThat(longTermMemories.findById(first.memory().memoryId()).orElseThrow().getFullContent())
                .doesNotContain("candidate conflict evidence");
        }
    }

    @Test
    void nonConflictingFactStillMergesWithEvidenceLedger() {
        var first = memoryRefineryService.refine(conflictCandidate(
            "issue04-non-conflict-a",
            "issue04-non-conflict-task-a",
            "businessEntity",
            "payment"
        ));
        var merged = memoryRefineryService.refine(conflictCandidate(
            "issue04-non-conflict-b",
            "issue04-non-conflict-task-b",
            "businessEntity",
            "payment"
        ));

        assertThat(merged.accepted()).isTrue();
        assertThat(merged.created()).isFalse();
        assertThat(merged.duplicateSuppressed()).isTrue();
        assertThat(merged.memory().memoryId()).isEqualTo(first.memory().memoryId());
        assertThat(merged.auditSummary()).isEmpty();
        assertThat(merged.memory().metadata())
            .containsEntry("mergeCount", 2)
            .containsEntry("evidenceCount", 2);
    }

    private MemoryCandidateRequest conflictCandidate(
        String sourceRef,
        String taskId,
        String conflictField,
        String conflictValue
    ) {
        var metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("systemName", "billing");
        metadata.put("module", "payment");
        metadata.put("apiPath", "/api/payments/charge");
        metadata.put("httpMethod", "POST");
        metadata.put("errorCode", "GW_TIMEOUT");
        metadata.put("businessEntity", "payment");
        metadata.put("failureClassification", "gateway-timeout");
        metadata.put(conflictField, conflictValue);

        return new MemoryCandidateRequest(
            "Payment gateway timeout requires retry fixture",
            "Payment gateway timeout failures require the approved retry fixture before assertions.",
            MemorySourceType.EXECUTION_RESULT,
            sourceRef,
            taskId,
            List.of("payment", "timeout", "retry"),
            0.88f,
            sourceRef.endsWith("-b")
                ? "candidate conflict evidence should be rejected before merge."
                : "existing baseline evidence should stay unchanged.",
            metadata
        );
    }

    private record ConflictCase(String field, String existingValue, String candidateValue) {
    }
}
