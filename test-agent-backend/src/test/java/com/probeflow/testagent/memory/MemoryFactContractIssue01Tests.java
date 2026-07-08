package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.knowledge.EmbeddingProfileMetadata;
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
class MemoryFactContractIssue01Tests {

    @Autowired
    private MemoryRefineryService memoryRefineryService;

    @Test
    void writesFailureFactWithEvidenceIdentityFingerprintAndEmbeddingProfileMetadata() {
        var result = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Payment GW_TIMEOUT fails without retry fixture",
            "Payment charge failures with GW_TIMEOUT should use the approved retry fixture before assertions.",
            MemorySourceType.EXECUTION_RESULT,
            "issue01-failure-source",
            "issue01-task",
            List.of("payment", "retry"),
            0.88f,
            "Execution evidence: POST /api/payments/charge returned GW_TIMEOUT until retry fixture was enabled.",
            Map.of(
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "errorCode", "GW_TIMEOUT"
            )
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.created()).isTrue();
        assertThat(result.duplicateSuppressed()).isFalse();
        assertThat(result.rejectionReason()).isNull();
        assertThat(result.memory().scopeType()).isEqualTo(MemoryScopeType.FAILURE_PATTERN);
        assertThat(result.memory().metadata())
            .containsEntry("factType", "failure_pattern")
            .containsEntry("qualityStatus", "ACCEPTED")
            .containsEntry("factApplicability", "system=billing; module=payment; api=/api/payments/charge; error=GW_TIMEOUT")
            .containsEntry("factTrigger", "when GW_TIMEOUT or similar symptoms appear")
            .containsKey("factFingerprint")
            .containsKey("identityHints")
            .containsKey("evidenceSummary")
            .containsKey("evidenceLedger")
            .containsKey(EmbeddingProfileMetadata.EMBEDDING_PROFILE);
        assertThat((Map<String, Object>) result.memory().metadata().get("identityHints"))
            .containsEntry("systemName", "billing")
            .containsEntry("module", "payment")
            .containsEntry("apiPath", "/api/payments/charge")
            .containsEntry("errorCode", "GW_TIMEOUT");
        assertThat((List<Map<String, Object>>) result.memory().metadata().get("evidenceLedger"))
            .singleElement()
            .satisfies(entry -> assertThat(entry)
                .containsEntry("sourceType", "EXECUTION_RESULT")
                .containsEntry("sourceRef", "issue01-failure-source")
                .containsEntry("taskId", "issue01-task"));
        assertThat(result.memory().embedding()).hasSize(1024);
    }

    @Test
    void deterministicExtractorClassifiesCoreFactTypes() {
        var testing = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Payment setup checklist",
            "Test setup should provision tenant fixtures and assert idempotency headers.",
            MemorySourceType.MANUAL,
            "issue01-testing",
            "issue01-task-testing",
            List.of("setup", "test"),
            0.82f,
            "Reviewer accepted this reusable setup checklist for payment tests.",
            Map.of("module", "payment")
        ));
        var project = memoryRefineryService.refine(new MemoryCandidateRequest(
            "Order service requires tenant auth header",
            "Order service endpoints require X-Tenant-Id and bearer auth for stable cross-task execution.",
            MemorySourceType.MANUAL,
            "issue01-project",
            "issue01-task-project",
            List.of("order", "tenant"),
            0.84f,
            "Project documentation confirmed the tenant auth rule.",
            Map.of("systemName", "order-platform", "module", "order")
        ));
        var preference = memoryRefineryService.refine(new MemoryCandidateRequest(
            "User prefers compact assertion summaries",
            "Prefer concise assertion summaries with concrete expected and actual values.",
            MemorySourceType.USER_FEEDBACK,
            "issue01-preference",
            "issue01-task-preference",
            List.of("reporting"),
            0.86f,
            "Human reviewer promoted the compact assertion summary style.",
            Map.of("module", "reporting")
        ));

        assertThat(testing.memory().metadata()).containsEntry("factType", "testing_pattern");
        assertThat(project.memory().metadata()).containsEntry("factType", "project_knowledge");
        assertThat(preference.memory().metadata()).containsEntry("factType", "preference");
        assertThat(testing.memory().scopeType()).isEqualTo(MemoryScopeType.TESTING_PATTERN);
        assertThat(project.memory().scopeType()).isEqualTo(MemoryScopeType.PROJECT_KNOWLEDGE);
        assertThat(preference.memory().scopeType()).isEqualTo(MemoryScopeType.PREFERENCE);
    }
}
