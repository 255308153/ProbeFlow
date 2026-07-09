package com.probeflow.testagent.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.llm.LlmErrorType;
import com.probeflow.testagent.llm.LlmProvider;
import com.probeflow.testagent.llm.LlmProviderException;
import com.probeflow.testagent.llm.LlmRequest;
import com.probeflow.testagent.llm.LlmResponse;
import com.probeflow.testagent.llm.LlmTokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmRerankManualProfileIssue04Tests {

    @Test
    void disabledProfileFallsBackToDeterministicRerankWithoutCallingProvider() {
        var provider = new StubLlmProvider(successJson());
        var service = new LlmRerankService(provider, LlmRerankProfile.disabled());

        var result = service.rerank(new LlmRerankRequest(
            "payment PAY_401 tenant bootstrap",
            List.of(knowledgeCandidate(), memoryCandidate()),
            Map.of()
        ));

        assertThat(result.usedProvider()).isFalse();
        assertThat(result.usedFallback()).isTrue();
        assertThat(result.error().type()).isEqualTo(LlmRerankErrorType.DISABLED);
        assertThat(provider.requests).isEmpty();
        assertThat(result.output().items()).extracting(item -> item.candidate().candidateIdentity().stableKey())
            .containsOnly("knowledge:pay-401-runbook", "memory:tenant-bootstrap-memory");
    }

    @Test
    void buildsSafePromptAndAppliesStructuredLlmRanksFromKnownCandidatesOnly() {
        var provider = new StubLlmProvider(successJson());
        var service = new LlmRerankService(provider, LlmRerankProfile.manualDeepSeekV4Pro("fake"));

        var result = service.rerank(new LlmRerankRequest(
            "why did /api/payments fail with PAY_401",
            List.of(knowledgeCandidate(), memoryCandidate()),
            Map.of("traceId", "issue04")
        ));

        assertThat(result.usedProvider()).isTrue();
        assertThat(result.usedFallback()).isFalse();
        assertThat(result.output().items()).extracting(item -> item.candidate().candidateIdentity().stableKey())
            .containsExactly("memory:tenant-bootstrap-memory", "knowledge:pay-401-runbook");
        assertThat(result.output().items().getFirst().rerankScore()).isEqualTo(0.93d);
        assertThat(result.output().items().getFirst().reasons()).contains(
            "llm-rank:1",
            "llm-confidence:0.9300",
            "historical evidence explains the failure"
        );

        assertThat(provider.requests).hasSize(1);
        var request = provider.requests.getFirst();
        assertThat(request.provider()).isEqualTo("fake");
        assertThat(request.model()).isEqualTo("deepseek-v4-pro");
        assertThat(request.purpose()).isEqualTo("V5_5_LLM_RERANK");
        assertThat(request.prompt())
            .contains("Return only JSON")
            .contains("candidateId")
            .contains("rank")
            .contains("reason")
            .contains("confidence")
            .contains("Do not create new candidate ids")
            .contains("Do not create new citations")
            .contains("knowledge:pay-401-runbook")
            .contains("memory:tenant-bootstrap-memory")
            .contains("metadata-exact")
            .contains("qv-failure-pattern")
            .doesNotContain("Authorization")
            .doesNotContain("Bearer should-not-leak")
            .doesNotContain("sk-test")
            .doesNotContain("tenant-token")
            .doesNotContain("session-cookie")
            .doesNotContain("password")
            .doesNotContain("rawRequestBody");
    }

    @Test
    void invalidJsonFallsBackToDeterministicBaselineWithRedactedDiagnostic() {
        var service = new LlmRerankService(
            new StubLlmProvider("not-json Authorization: Bearer sk-live-secret"),
            LlmRerankProfile.manualDeepSeekV4Pro("fake")
        );

        var result = service.rerank(new LlmRerankRequest(
            "payment PAY_401 tenant bootstrap",
            List.of(knowledgeCandidate(), memoryCandidate()),
            Map.of()
        ));

        assertThat(result.usedFallback()).isTrue();
        assertThat(result.error().type()).isEqualTo(LlmRerankErrorType.INVALID_OUTPUT);
        assertThat(result.error().diagnostic())
            .contains("invalid structured output")
            .doesNotContain("Authorization")
            .doesNotContain("sk-live-secret");
    }

    @Test
    void unknownCandidateIdsAreRejectedAndFallBack() {
        var service = new LlmRerankService(
            new StubLlmProvider("""
                {"results":[
                  {"candidateId":"knowledge:made-up","rank":1,"reason":"hallucinated","confidence":0.99}
                ]}
                """),
            LlmRerankProfile.manualDeepSeekV4Pro("fake")
        );

        var result = service.rerank(new LlmRerankRequest(
            "payment PAY_401 tenant bootstrap",
            List.of(knowledgeCandidate(), memoryCandidate()),
            Map.of()
        ));

        assertThat(result.usedFallback()).isTrue();
        assertThat(result.error().type()).isEqualTo(LlmRerankErrorType.UNKNOWN_CANDIDATE);
        assertThat(result.error().diagnostic()).contains("unknown candidate id").doesNotContain("knowledge:made-up");
    }

    @Test
    void inventedEvidenceReferencesAreRejectedAndFallBack() {
        var service = new LlmRerankService(
            new StubLlmProvider("""
                {"results":[
                  {"candidateId":"knowledge:pay-401-runbook","rank":1,"reason":"looks useful","confidence":0.91,
                   "evidenceRefs":["route:invented-route"]},
                  {"candidateId":"memory:tenant-bootstrap-memory","rank":2,"reason":"secondary","confidence":0.72,
                   "evidenceRefs":["route:graph-memory"]}
                ]}
                """),
            LlmRerankProfile.manualDeepSeekV4Pro("fake")
        );

        var result = service.rerank(new LlmRerankRequest(
            "payment PAY_401 tenant bootstrap",
            List.of(knowledgeCandidate(), memoryCandidate()),
            Map.of()
        ));

        assertThat(result.usedFallback()).isTrue();
        assertThat(result.error().type()).isEqualTo(LlmRerankErrorType.INVALID_EVIDENCE);
        assertThat(result.error().diagnostic()).contains("unknown evidence reference").doesNotContain("invented-route");
    }

    @Test
    void providerTimeoutAndRemoteErrorsFallBackWithoutLeakingProviderMessages() {
        var timeout = new LlmRerankService(
            request -> {
                throw new LlmProviderException(
                    LlmErrorType.TIMEOUT,
                    "timeout Authorization: Bearer sk-live-secret token=tenant-secret"
                );
            },
            LlmRerankProfile.manualDeepSeekV4Pro("fake")
        );
        var remote = new LlmRerankService(
            request -> {
                throw new LlmProviderException(
                    LlmErrorType.PROVIDER_ERROR,
                    "remote cookie=session-cookie rawRequestBody={password}"
                );
            },
            LlmRerankProfile.manualDeepSeekV4Pro("fake")
        );

        var timeoutResult = timeout.rerank(new LlmRerankRequest(
            "payment PAY_401 tenant bootstrap",
            List.of(knowledgeCandidate(), memoryCandidate()),
            Map.of()
        ));
        var remoteResult = remote.rerank(new LlmRerankRequest(
            "payment PAY_401 tenant bootstrap",
            List.of(knowledgeCandidate(), memoryCandidate()),
            Map.of()
        ));

        assertThat(timeoutResult.usedFallback()).isTrue();
        assertThat(timeoutResult.error().type()).isEqualTo(LlmRerankErrorType.TIMEOUT);
        assertThat(timeoutResult.error().diagnostic())
            .contains("TIMEOUT")
            .doesNotContain("sk-live-secret")
            .doesNotContain("tenant-secret");
        assertThat(remoteResult.usedFallback()).isTrue();
        assertThat(remoteResult.error().type()).isEqualTo(LlmRerankErrorType.REMOTE_ERROR);
        assertThat(remoteResult.error().diagnostic())
            .contains("REMOTE_ERROR")
            .doesNotContain("session-cookie")
            .doesNotContain("password");
    }

    private static String successJson() {
        return """
            {"results":[
              {"candidateId":"memory:tenant-bootstrap-memory","rank":1,
               "reason":"historical evidence explains the failure","confidence":0.93,
               "evidenceRefs":["route:graph-memory","queryVariant:qv-failure-pattern"]},
              {"candidateId":"knowledge:pay-401-runbook","rank":2,
               "reason":"runbook confirms the error semantics","confidence":0.78,
               "evidenceRefs":["route:metadata-exact","queryVariant:qv-error-code"]}
            ]}
            """;
    }

    private RerankCandidate knowledgeCandidate() {
        return new RerankCandidate(
            RerankCorpusType.KNOWLEDGE,
            new RerankCandidateIdentity(RerankCorpusType.KNOWLEDGE, "pay-401-runbook"),
            new RerankSourceIdentity("ERROR_CODE_GUIDE", "wiki/payment-errors.md", Map.of(
                "chunkId", "pay-401-runbook",
                "documentRevisionId", "rev-payment-v4"
            )),
            "PAY_401 runbook",
            "PAY_401 means tenant bootstrap is missing before payment authorization.",
            2,
            0.82d,
            new RerankRouteEvidence(
                List.of("qv-error-code", "qv-api-path"),
                List.of(
                    new RerankMatchedRoute("metadata-exact", 1, 0.96d),
                    new RerankMatchedRoute("rewritten-semantic", 2, 0.78d)
                ),
                Map.of(),
                Map.of(),
                0.82d
            ),
            new RerankFeatureLedger(
                0.82d,
                0.81d,
                0.65d,
                0.82d,
                1.0d,
                0.91d,
                1.0d,
                0.88d,
                null,
                null,
                null,
                null,
                null,
                false,
                List.of(),
                96,
                List.of()
            ),
            Map.of(
                "Authorization", "Bearer should-not-leak",
                "apiKey", "sk-test",
                "token", "tenant-token",
                "cookie", "session-cookie",
                "rawRequestBody", "{\"password\":\"secret\"}"
            ),
            96,
            List.of()
        );
    }

    private RerankCandidate memoryCandidate() {
        return new RerankCandidate(
            RerankCorpusType.MEMORY,
            new RerankCandidateIdentity(RerankCorpusType.MEMORY, "tenant-bootstrap-memory"),
            new RerankSourceIdentity("OBSERVATION", "ltm/payment/pay-401", Map.of(
                "memoryId", "tenant-bootstrap-memory",
                "factFingerprint", "fact-tenant-bootstrap"
            )),
            "Tenant bootstrap prevents PAY_401",
            "Restoring tenant bootstrap fixed PAY_401 before payment auth in suite run 42.",
            1,
            0.72d,
            new RerankRouteEvidence(
                List.of("qv-failure-pattern"),
                List.of(new RerankMatchedRoute("graph-memory", 1, 0.84d)),
                Map.of(),
                Map.of(),
                0.72d
            ),
            new RerankFeatureLedger(
                0.76d,
                0.64d,
                0.42d,
                0.72d,
                1.0d,
                0.86d,
                null,
                0.80d,
                0.92d,
                0.86d,
                0.73d,
                0.84d,
                2,
                false,
                List.of(),
                120,
                List.of()
            ),
            Map.of(
                "Authorization", "Bearer should-not-leak",
                "apiKey", "sk-test",
                "token", "tenant-token",
                "cookie", "session-cookie",
                "rawRequestBody", "{\"password\":\"secret\"}"
            ),
            120,
            List.of()
        );
    }

    private static final class StubLlmProvider implements LlmProvider {
        private final String text;
        private final List<LlmRequest> requests = new ArrayList<>();

        private StubLlmProvider(String text) {
            this.text = text;
        }

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public LlmResponse generate(LlmRequest request) {
            requests.add(request);
            return new LlmResponse(
                request.provider(),
                request.model(),
                text,
                LlmTokenUsage.of(40, 20),
                "stub-trace",
                true,
                Map.of()
            );
        }
    }
}
