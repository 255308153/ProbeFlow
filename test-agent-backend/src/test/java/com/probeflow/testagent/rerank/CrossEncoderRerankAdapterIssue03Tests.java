package com.probeflow.testagent.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrossEncoderRerankAdapterIssue03Tests {

    @Test
    void buildsMinimalProviderRequestFromExistingRerankCandidatesWithSafeMetadataOnly() {
        var provider = new CapturingProvider(List.of(
            new CrossEncoderRerankProviderResult("knowledge:pay-401-runbook", 0.92d, 1, "exact payment error match"),
            new CrossEncoderRerankProviderResult("memory:tenant-bootstrap-memory", 0.64d, 2, "related historical memory")
        ));
        var service = new CrossEncoderRerankService(provider);

        var result = service.rerank(
            new CrossEncoderRerankRequest(
                "why did /api/payments fail with PAY_401",
                List.of(knowledgeCandidate(), memoryCandidate()),
                Map.of("traceId", "manual-check-1")
            )
        );

        assertThat(result.usedProvider()).isTrue();
        assertThat(result.usedFallback()).isFalse();
        assertThat(provider.requests).hasSize(1);

        var request = provider.requests.getFirst();
        assertThat(request.query()).isEqualTo("why did /api/payments fail with PAY_401");
        assertThat(request.candidates()).hasSize(2);
        assertThat(request.candidates()).extracting(CrossEncoderRerankProviderCandidate::candidateId)
            .containsExactly("knowledge:pay-401-runbook", "memory:tenant-bootstrap-memory");
        assertThat(request.candidates().getFirst().text())
            .isEqualTo("PAY_401 means tenant bootstrap is missing before payment authorization.");
        assertThat(request.candidates().getFirst().metadata())
            .containsEntry("corpusType", "KNOWLEDGE")
            .containsEntry("sourceType", "ERROR_CODE_GUIDE")
            .containsEntry("beforeRank", 2)
            .containsEntry("fusedScore", 0.82d)
            .containsEntry("matchedRoutes", List.of("metadata-exact", "rewritten-semantic"))
            .doesNotContainKeys("Authorization", "apiKey", "token", "rawRequestBody");
        assertThat(request.candidates().get(1).metadata())
            .containsEntry("corpusType", "MEMORY")
            .containsEntry("sourceType", "OBSERVATION")
            .doesNotContainKeys("Authorization", "apiKey", "token", "rawRequestBody");
    }

    @Test
    void appliesProviderScoresAndReasonsWhilePreservingBeforeAndAfterRankExplanation() {
        var service = new CrossEncoderRerankService(new CapturingProvider(List.of(
            new CrossEncoderRerankProviderResult("memory:tenant-bootstrap-memory", 0.98d, 1, "run history is stronger"),
            new CrossEncoderRerankProviderResult("knowledge:pay-401-runbook", 0.70d, 2, "still relevant")
        )));

        var result = service.rerank(new CrossEncoderRerankRequest(
            "payment PAY_401 tenant bootstrap",
            List.of(knowledgeCandidate(), memoryCandidate()),
            Map.of()
        ));

        assertThat(result.output().items()).extracting(item -> item.candidate().candidateIdentity().stableKey())
            .containsExactly("memory:tenant-bootstrap-memory", "knowledge:pay-401-runbook");

        var promoted = result.output().items().getFirst();
        assertThat(promoted.beforeRank()).isEqualTo(1);
        assertThat(promoted.afterRank()).isEqualTo(1);
        assertThat(promoted.rerankScore()).isEqualTo(0.98d);
        assertThat(promoted.reasons()).contains(
            "cross-encoder-model-score",
            "cross-encoder-rank:1",
            "run history is stronger"
        );
        assertThat(promoted.scoreExplanation()).contains(
            "memory:tenant-bootstrap-memory",
            "rerankScore=0.9800",
            "cross-encoder-model-score"
        );
    }

    @Test
    void rejectsUnknownCandidateIdsAndFallsBackToDeterministicBaseline() {
        var service = new CrossEncoderRerankService(new CapturingProvider(List.of(
            new CrossEncoderRerankProviderResult("knowledge:made-up", 0.99d, 1, "hallucinated id")
        )));

        var result = service.rerank(new CrossEncoderRerankRequest(
            "payment PAY_401 tenant bootstrap",
            List.of(knowledgeCandidate(), memoryCandidate()),
            Map.of()
        ));

        assertThat(result.usedProvider()).isFalse();
        assertThat(result.usedFallback()).isTrue();
        assertThat(result.error()).isNotNull();
        assertThat(result.error().type()).isEqualTo(CrossEncoderRerankErrorType.INVALID_RESPONSE);
        assertThat(result.error().diagnostic()).contains("unknown candidate id").doesNotContain("hallucinated id");
        assertThat(result.output().items()).extracting(item -> item.candidate().candidateIdentity().stableKey())
            .containsOnly("knowledge:pay-401-runbook", "memory:tenant-bootstrap-memory");
    }

    @Test
    void classifiesMissingScoreAndPartialResultsThenFallsBackToDeterministicBaseline() {
        var missingScore = new CrossEncoderRerankService(new CapturingProvider(List.of(
            new CrossEncoderRerankProviderResult("knowledge:pay-401-runbook", null, 1, "no score"),
            new CrossEncoderRerankProviderResult("memory:tenant-bootstrap-memory", 0.7d, 2, "has score")
        )));
        var partial = new CrossEncoderRerankService(new CapturingProvider(List.of(
            new CrossEncoderRerankProviderResult("knowledge:pay-401-runbook", 0.7d, 1, "only one candidate")
        )));

        var missingScoreResult = missingScore.rerank(new CrossEncoderRerankRequest(
            "payment PAY_401 tenant bootstrap",
            List.of(knowledgeCandidate(), memoryCandidate()),
            Map.of()
        ));
        var partialResult = partial.rerank(new CrossEncoderRerankRequest(
            "payment PAY_401 tenant bootstrap",
            List.of(knowledgeCandidate(), memoryCandidate()),
            Map.of()
        ));

        assertThat(missingScoreResult.usedFallback()).isTrue();
        assertThat(missingScoreResult.error().type()).isEqualTo(CrossEncoderRerankErrorType.MISSING_SCORE);
        assertThat(partialResult.usedFallback()).isTrue();
        assertThat(partialResult.error().type()).isEqualTo(CrossEncoderRerankErrorType.PARTIAL_RESULT);
    }

    @Test
    void classifiesProviderFailuresAndRedactsSecretsOrRequestContentFromDiagnostics() {
        var service = new CrossEncoderRerankService(request -> {
            throw new CrossEncoderRerankProviderException(
                CrossEncoderRerankErrorType.TIMEOUT,
                "Authorization: Bearer sk-live-secret token=tenant-secret "
                    + "PAY_401 means tenant bootstrap is missing before payment authorization."
            );
        });

        var result = service.rerank(new CrossEncoderRerankRequest(
            "payment PAY_401 tenant bootstrap",
            List.of(knowledgeCandidate(), memoryCandidate()),
            Map.of()
        ));

        assertThat(result.usedFallback()).isTrue();
        assertThat(result.error().type()).isEqualTo(CrossEncoderRerankErrorType.TIMEOUT);
        assertThat(result.error().diagnostic())
            .contains("Cross Encoder provider failed with TIMEOUT")
            .doesNotContain("Authorization")
            .doesNotContain("Bearer")
            .doesNotContain("sk-live-secret")
            .doesNotContain("tenant-secret")
            .doesNotContain("PAY_401 means tenant bootstrap");
    }

    @Test
    void classifiesUnexpectedProviderRuntimeFailureAsRemoteErrorFallback() {
        var service = new CrossEncoderRerankService(request -> {
            throw new IllegalStateException("provider unavailable");
        });

        var result = service.rerank(new CrossEncoderRerankRequest(
            "payment PAY_401 tenant bootstrap",
            List.of(knowledgeCandidate(), memoryCandidate()),
            Map.of()
        ));

        assertThat(result.usedFallback()).isTrue();
        assertThat(result.error().type()).isEqualTo(CrossEncoderRerankErrorType.REMOTE_ERROR);
        assertThat(result.error().diagnostic())
            .contains("Cross Encoder provider failed with REMOTE_ERROR")
            .doesNotContain("provider unavailable");
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
                "rawRequestBody", "{\"password\":\"secret\"}",
                "safeTag", "payment"
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
                "rawRequestBody", "{\"password\":\"secret\"}",
                "safeTag", "payment"
            ),
            120,
            List.of()
        );
    }

    private static final class CapturingProvider implements CrossEncoderRerankProvider {
        private final List<CrossEncoderRerankProviderResult> results;
        private final List<CrossEncoderRerankProviderRequest> requests = new ArrayList<>();

        private CapturingProvider(List<CrossEncoderRerankProviderResult> results) {
            this.results = results;
        }

        @Override
        public CrossEncoderRerankProviderResponse rerank(CrossEncoderRerankProviderRequest request) {
            requests.add(request);
            return new CrossEncoderRerankProviderResponse(results);
        }
    }
}
