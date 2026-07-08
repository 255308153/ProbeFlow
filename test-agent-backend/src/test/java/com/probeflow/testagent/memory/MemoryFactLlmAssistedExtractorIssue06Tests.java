package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.llm.FakeLlmProvider;
import com.probeflow.testagent.llm.LlmErrorType;
import com.probeflow.testagent.llm.LlmProviderException;
import com.probeflow.testagent.llm.LlmRequest;
import com.probeflow.testagent.llm.LlmResponse;
import com.probeflow.testagent.llm.LlmTokenUsage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "probeflow.memory.fact-extractor.mode=llm-assisted")
@ActiveProfiles("test")
@Transactional
class MemoryFactLlmAssistedExtractorIssue06Tests {

    @Autowired
    private MemoryRefineryService memoryRefineryService;

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @MockBean
    private FakeLlmProvider fakeLlmProvider;

    @Test
    void validLlmFactIsParsedSanitizedAndWrittenThroughRefinery() {
        when(fakeLlmProvider.providerName()).thenReturn("fake");
        when(fakeLlmProvider.generate(any())).thenReturn(llmResponse("""
            {
              "factType": "failure_pattern",
              "summary": "Payment timeout requires approved retry fixture",
              "content": "Payment gateway timeout failures require the approved retry fixture before assertions.",
              "fullContent": "Evidence confirms the retry fixture recovered GW_TIMEOUT without storing secrets.",
              "applicability": "module payment; api /api/payments/charge",
              "trigger": "when GW_TIMEOUT appears",
              "tags": ["payment", "timeout", "retry"],
              "identityHints": {"module": "payment", "apiPath": "/api/payments/charge", "errorCode": "GW_TIMEOUT"},
              "evidence": [
                {"summary": "Execution confirmed GW_TIMEOUT recovery", "sanitizedEvidence": "Retry fixture recovered payment timeout."}
              ],
              "confidence": 0.86,
              "importance": 0.80,
              "reuseScore": 0.78,
              "fingerprint": "llm-fact:payment-timeout-retry"
            }
            """));

        var result = memoryRefineryService.refine(candidate("issue06-valid-a", "issue06-task-a", 0.86f));

        assertThat(result.accepted()).isTrue();
        assertThat(result.created()).isTrue();
        assertThat(result.memory().summary()).isEqualTo("Payment timeout requires approved retry fixture");
        assertThat(result.memory().metadata())
            .containsEntry("factType", "failure_pattern")
            .containsEntry("factFingerprint", "llm-fact:payment-timeout-retry")
            .containsEntry("qualityStatus", "ACCEPTED");
        assertThat((List<Map<String, Object>>) result.memory().metadata().get("evidenceLedger"))
            .singleElement()
            .satisfies(entry -> assertThat(entry.get("sanitizedEvidence")).isEqualTo("Retry fixture recovered payment timeout."));
        verify(fakeLlmProvider).generate(any(LlmRequest.class));
    }

    @Test
    void invalidJsonAndMissingRequiredFieldsFailWithSchemaReasons() {
        when(fakeLlmProvider.providerName()).thenReturn("fake");
        when(fakeLlmProvider.generate(any()))
            .thenReturn(llmResponse("not json"))
            .thenReturn(llmResponse("""
                {"summary":"Payment timeout requires retry evidence","content":"Payment retry content","evidence":[{"summary":"observed","sanitizedEvidence":"observed"}]}
                """))
            .thenReturn(llmResponse("""
                {"factType":"failure_pattern","content":"Payment retry content","evidence":[{"summary":"observed","sanitizedEvidence":"observed"}]}
                """))
            .thenReturn(llmResponse("""
                {"factType":"failure_pattern","summary":"Payment timeout requires retry evidence","content":"Payment retry content"}
                """));

        assertThatThrownBy(() -> memoryRefineryService.refine(candidate("issue06-invalid-json", "issue06-task-b1", 0.84f)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("llm-fact-schema-invalid: invalid-json");
        assertThatThrownBy(() -> memoryRefineryService.refine(candidate("issue06-missing-type", "issue06-task-b2", 0.84f)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("llm-fact-schema-invalid: missing-fact-type");
        assertThatThrownBy(() -> memoryRefineryService.refine(candidate("issue06-missing-summary", "issue06-task-b3", 0.84f)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("llm-fact-schema-invalid: missing-summary");
        assertThatThrownBy(() -> memoryRefineryService.refine(candidate("issue06-missing-evidence", "issue06-task-b4", 0.84f)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("llm-fact-schema-invalid: missing-evidence");
        assertThat(longTermMemories.findAll()).isEmpty();
    }

    @Test
    void sensitiveLlmOutputIsRedactedBeforeItCanReachLongTermMemory() {
        when(fakeLlmProvider.providerName()).thenReturn("fake");
        when(fakeLlmProvider.generate(any())).thenReturn(llmResponse("""
            {
              "factType": "project_knowledge",
              "summary": "Auth failures require tenant scoped token refresh",
              "content": "Use tenant scoped refresh when API reports 401. Authorization: Bearer llm-secret api_key=llm-key token=llm-token",
              "fullContent": "password=llm-password should not persist",
              "tags": ["auth", "tenant"],
              "identityHints": {"module": "auth", "apiPath": "/api/auth/refresh", "errorCode": "AUTH_401"},
              "evidence": [
                {"summary": "Authorization: Bearer evidence-secret", "sanitizedEvidence": "api_key=evidence-key token=evidence-token"}
              ],
              "confidence": 0.82,
              "importance": 0.73,
              "reuseScore": 0.76
            }
            """));

        var result = memoryRefineryService.refine(candidate("issue06-sensitive", "issue06-task-c", 0.82f));

        assertThat(result.accepted()).isTrue();
        var persisted = longTermMemories.findById(result.memory().memoryId()).orElseThrow();
        assertThat(persisted.getSummary() + persisted.getContent() + persisted.getFullContent() + persisted.getMetadata())
            .doesNotContain("llm-secret")
            .doesNotContain("llm-key")
            .doesNotContain("llm-token")
            .doesNotContain("llm-password")
            .doesNotContain("evidence-secret")
            .doesNotContain("evidence-key")
            .doesNotContain("evidence-token");
    }

    @Test
    void llmFactStillPassesQualityGate() {
        when(fakeLlmProvider.providerName()).thenReturn("fake");
        when(fakeLlmProvider.generate(any())).thenReturn(llmResponse("""
            {
              "factType": "project_knowledge",
              "summary": "pay attention",
              "content": "be careful",
              "evidence": [
                {"summary": "observed once", "sanitizedEvidence": "observed once"}
              ],
              "confidence": 0.90,
              "importance": 0.70,
              "reuseScore": 0.70
            }
            """));

        var result = memoryRefineryService.refine(candidate("issue06-low-quality", "issue06-task-d", 0.90f));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo("too-generic");
        assertThat(longTermMemories.findAll()).isEmpty();
    }

    @Test
    void llmFactsStillUseDedupMergeAndIdentityConflictGuard() {
        when(fakeLlmProvider.providerName()).thenReturn("fake");
        when(fakeLlmProvider.generate(any()))
            .thenReturn(llmResponse(paymentFact("payment", "Payment timeout execution A", "issue06-shared-fingerprint")))
            .thenReturn(llmResponse(paymentFact("payment", "Payment timeout execution B", "issue06-shared-fingerprint")))
            .thenReturn(llmResponse(paymentFact("refund", "Refund timeout execution C", "issue06-shared-fingerprint")));

        var first = memoryRefineryService.refine(candidate("issue06-dedup-a", "issue06-task-e1", 0.84f));
        var merged = memoryRefineryService.refine(candidate("issue06-dedup-b", "issue06-task-e2", 0.88f));
        var conflict = memoryRefineryService.refine(candidate("issue06-conflict-c", "issue06-task-e3", 0.88f));

        assertThat(first.created()).isTrue();
        assertThat(merged.accepted()).isTrue();
        assertThat(merged.created()).isFalse();
        assertThat(merged.duplicateSuppressed()).isTrue();
        assertThat(merged.memory().memoryId()).isEqualTo(first.memory().memoryId());
        assertThat(merged.memory().metadata())
            .containsEntry("mergeCount", 2)
            .containsEntry("evidenceCount", 2);
        assertThat(conflict.accepted()).isFalse();
        assertThat(conflict.rejectionReason()).isEqualTo("identity-conflict");
        assertThat(conflict.auditSummary()).containsEntry("reason", "identity-conflict");
        assertThat(longTermMemories.findAll()).hasSize(1);
    }

    @Test
    void providerErrorsAreReportedWithoutLeakingSecrets() {
        when(fakeLlmProvider.providerName()).thenReturn("fake");
        when(fakeLlmProvider.generate(any())).thenThrow(new LlmProviderException(
            LlmErrorType.PROVIDER_ERROR,
            "Authorization: Bearer provider-secret api_key=provider-key token=provider-token prompt secret",
            "trace-secret",
            null
        ));

        assertThatThrownBy(() -> memoryRefineryService.refine(candidate("issue06-provider-error", "issue06-task-f", 0.84f)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("llm-fact-extraction-failed")
            .hasMessageNotContaining("provider-secret")
            .hasMessageNotContaining("provider-key")
            .hasMessageNotContaining("provider-token");
    }

    private String paymentFact(String module, String evidenceSummary, String fingerprint) {
        return """
            {
              "factType": "failure_pattern",
              "summary": "Gateway timeout requires shorter retry windows",
              "content": "Retry gateway timeouts with a tighter assertion and retry window.",
              "fullContent": "%s",
              "tags": ["payment", "timeout", "retry"],
              "identityHints": {"systemName": "billing", "module": "%s", "apiPath": "/api/payments/charge", "errorCode": "GW_TIMEOUT"},
              "evidence": [
                {"summary": "%s", "sanitizedEvidence": "%s"}
              ],
              "confidence": 0.86,
              "importance": 0.78,
              "reuseScore": 0.80,
              "fingerprint": "%s"
            }
            """.formatted(evidenceSummary, module, evidenceSummary, evidenceSummary, fingerprint);
    }

    private MemoryCandidateRequest candidate(String sourceRef, String taskId, float confidence) {
        return new MemoryCandidateRequest(
            "Payment gateway timeout requires reusable retry handling",
            "Payment gateway timeout failures require reusable retry fixture evidence across tasks.",
            MemorySourceType.EXECUTION_RESULT,
            sourceRef,
            taskId,
            List.of("payment", "timeout", "retry"),
            confidence,
            "Execution evidence from " + sourceRef + " confirmed reusable payment timeout behavior.",
            Map.of("module", "payment", "apiPath", "/api/payments/charge", "errorCode", "GW_TIMEOUT")
        );
    }

    private LlmResponse llmResponse(String text) {
        return new LlmResponse("fake", "fake-model", text, LlmTokenUsage.of(20, 40), "trace-issue06", true, Map.of());
    }
}
