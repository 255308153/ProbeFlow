package com.probeflow.testagent.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.knowledge.DocumentType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RerankEvaluationDocsAcceptanceIssue08Tests {

    private final DeterministicRerankEngine rerank = new DeterministicRerankEngine();
    private final KnowledgeContextExpander knowledgeExpander = new KnowledgeContextExpander();
    private final MemoryEvidenceExpander memoryExpander = new MemoryEvidenceExpander();

    @Test
    void offlineEvaluationFixtureComparesV54FusedRankWithV55PostRerankRankForTwoStages() {
        var caseGeneration = evaluate(
            "case_generation",
            caseGenerationCandidates(),
            "spec-pagination-max",
            "old-pagination-bulk-note"
        );
        var failureAnalysis = evaluate(
            "failure_analysis",
            failureAnalysisCandidates(),
            "err-pay-401-cause",
            "pay-401-stale-bloated-runbook"
        );

        assertThat(List.of(caseGeneration, failureAnalysis))
            .extracting(ScenarioEvaluation::stage)
            .containsExactly("case_generation", "failure_analysis");

        assertThat(caseGeneration.promoted().beforeRank()).isEqualTo(3);
        assertThat(caseGeneration.promoted().afterRank()).isEqualTo(1);
        assertThat(caseGeneration.promoted().reasons())
            .contains("stage-fit", "route-agreement", "exact-entity", "document-authority");
        assertThat(caseGeneration.demoted().beforeRank()).isEqualTo(1);
        assertThat(caseGeneration.demoted().afterRank()).isGreaterThan(caseGeneration.demoted().beforeRank());
        assertThat(caseGeneration.demoted().penalties())
            .contains("very-high-token-cost", "low-confidence", "conflict-signals");
        assertThat(caseGeneration.promotedExplanation())
            .contains(
                "stage=case_generation",
                "promoted knowledge:spec-pagination-max",
                "V5-4 fused rank=3",
                "V5-5 post-rerank rank=1",
                "stage-fit",
                "route-agreement",
                "exact-entity"
            );
        assertThat(caseGeneration.demotedExplanation())
            .contains(
                "demoted knowledge:old-pagination-bulk-note",
                "very-high-token-cost",
                "low-confidence",
                "conflict-signals"
            );

        assertThat(failureAnalysis.promoted().beforeRank()).isEqualTo(3);
        assertThat(failureAnalysis.promoted().afterRank()).isEqualTo(1);
        assertThat(failureAnalysis.promoted().reasons())
            .contains("stage-fit", "route-agreement", "exact-entity", "freshness");
        assertThat(failureAnalysis.demoted().beforeRank()).isEqualTo(1);
        assertThat(failureAnalysis.demoted().afterRank()).isGreaterThan(failureAnalysis.demoted().beforeRank());
        assertThat(failureAnalysis.demoted().penalties())
            .contains("very-high-token-cost", "low-confidence", "conflict-signals");
        assertThat(failureAnalysis.promotedExplanation())
            .contains(
                "stage=failure_analysis",
                "promoted knowledge:err-pay-401-cause",
                "V5-4 fused rank=3",
                "V5-5 post-rerank rank=1",
                "stage-fit",
                "exact-entity"
            );
    }

    @Test
    void smallToBigFixtureExpandsKnowledgeParentsAndMemoryEvidenceAfterRerank() {
        var caseGenerationOutput = rerank.rerank(caseGenerationCandidates());
        var failureOutput = rerank.rerank(failureAnalysisCandidates());
        var rerankedKnowledge = List.of(
            itemById(caseGenerationOutput, "knowledge:spec-pagination-max"),
            itemById(failureOutput, "knowledge:err-pay-401-cause")
        );

        var knowledgeResult = knowledgeExpander.expand(new KnowledgeExpansionRequest(
            rerankedKnowledge,
            knowledgeExpansionSources(),
            220
        ));

        assertThat(knowledgeResult.contexts()).hasSize(2);
        assertThat(knowledgeResult.contexts()).extracting(KnowledgeExpansionContext::expansionReason)
            .containsExactly(KnowledgeExpansionReason.TEST_SPEC_RULE_GROUP, KnowledgeExpansionReason.ERROR_CODE_ENTRY);
        assertThat(knowledgeContext(knowledgeResult, "spec-pagination-max").sources())
            .extracting(KnowledgeExpansionSource::chunkId)
            .containsExactly("spec-pagination-max", "spec-pagination-min");
        assertThat(knowledgeContext(knowledgeResult, "err-pay-401-cause").sources())
            .extracting(KnowledgeExpansionSource::chunkId)
            .containsExactly("err-pay-401-cause", "err-pay-401-action");
        assertThat(knowledgeContext(knowledgeResult, "err-pay-401-cause").citations())
            .extracting(KnowledgeExpansionCitation::role)
            .contains(KnowledgeExpansionCitationRole.ANCHOR, KnowledgeExpansionCitationRole.EXPANDED_CONTEXT);

        var memoryResult = memoryExpander.expand(new MemoryEvidenceExpansionRequest(
            List.of(itemById(failureOutput, "memory:mem-pay-401-evidence")),
            320
        ));

        assertThat(memoryResult.items()).hasSize(1);
        var memory = memoryResult.items().getFirst();
        assertThat(memory.anchor().anchorType()).isEqualTo(MemoryEvidenceAnchorType.GRAPH_RELATION);
        assertThat(memory.evidenceSummaries()).containsExactly(
            "PAY_401 reproduced before tenant bootstrap",
            "PAY_401 passed after tenant bootstrap restored auth state",
            "PAY_401 and tenant bootstrap co-occurred across regression suites"
        );
        assertThat(memory.mergedSourceRefs()).containsExactly("merged/payment-auth-bootstrap");
        assertThat(memory.graphRelation()).isNotNull();
        assertThat(memory.graphRelation().relationPath())
            .containsExactly("ERROR_CODE:PAY_401", "API_PATH:/api/orders/{orderId}/pay");
        assertThat(memory.citations()).extracting(MemoryEvidenceCitation::role)
            .contains(MemoryEvidenceRole.SUPPORTING, MemoryEvidenceRole.GRAPH_RELATION);
        assertThat(memory.positiveRecommendationEligible()).isTrue();
    }

    private ScenarioEvaluation evaluate(
        String stage,
        List<RerankCandidate> candidates,
        String promotedCandidateId,
        String demotedCandidateId
    ) {
        var output = rerank.rerank(candidates);
        var byStableKey = output.items().stream()
            .collect(Collectors.toMap(
                item -> item.candidate().candidateIdentity().stableKey(),
                Function.identity(),
                (first, ignored) -> first,
                LinkedHashMap::new
            ));
        return new ScenarioEvaluation(
            stage,
            output,
            byStableKey.get(stableKey(RerankCorpusType.KNOWLEDGE, promotedCandidateId)),
            byStableKey.get(stableKey(RerankCorpusType.KNOWLEDGE, demotedCandidateId))
        );
    }

    private List<RerankCandidate> caseGenerationCandidates() {
        return List.of(
            knowledgeCandidate(
                "old-pagination-bulk-note",
                "Old pagination bulk note",
                "A large stale note that mentions pagination but misses current boundary rules.",
                1,
                routes(
                    0.93d,
                    List.of("qv-case-original"),
                    new RerankMatchedRoute("lexical", 1, 0.93d)
                ),
                knowledgeLedger(
                    0.72d,
                    0.93d,
                    0.0d,
                    0.74d,
                    0.40d,
                    0.62d,
                    0.34d,
                    0.22d,
                    true,
                    List.of("contradicts-current-test-spec"),
                    2100
                ),
                Map.of()
            ),
            knowledgeCandidate(
                "generic-api-note",
                "Generic API note",
                "Generic API documentation has useful background but no pageSize boundary coverage.",
                2,
                routes(
                    0.82d,
                    List.of("qv-case-original"),
                    new RerankMatchedRoute("semantic", 2, 0.82d)
                ),
                knowledgeLedger(
                    0.42d,
                    0.82d,
                    0.0d,
                    0.74d,
                    0.58d,
                    0.44d,
                    0.58d,
                    0.54d,
                    false,
                    List.of(),
                    220
                ),
                Map.of()
            ),
            knowledgeCandidate(
                "spec-pagination-max",
                "Pagination boundary test spec",
                "pageSize above 200 must be rejected and paired with the lower-bound pagination rule.",
                3,
                routes(
                    0.74d,
                    List.of("qv-case-generation-test-spec", "qv-page-size-boundary"),
                    new RerankMatchedRoute("rewritten-semantic", 1, 0.82d),
                    new RerankMatchedRoute("metadata-exact", 1, 0.96d),
                    new RerankMatchedRoute("document-type:test_spec", 1, 0.88d)
                ),
                knowledgeLedger(
                    0.96d,
                    0.90d,
                    1.0d,
                    0.86d,
                    0.88d,
                    0.80d,
                    1.0d,
                    0.94d,
                    false,
                    List.of(),
                    42
                ),
                Map.of("scenario", "case_generation", "parentIdentity", "test_spec:pagination-boundary")
            )
        );
    }

    private List<RerankCandidate> failureAnalysisCandidates() {
        return List.of(
            knowledgeCandidate(
                "pay-401-stale-bloated-runbook",
                "Stale PAY_401 troubleshooting runbook",
                "A long old runbook that recommends retrying without tenant bootstrap evidence.",
                1,
                routes(
                    0.96d,
                    List.of("qv-failure-original"),
                    new RerankMatchedRoute("lexical", 1, 0.96d)
                ),
                knowledgeLedger(
                    0.65d,
                    0.96d,
                    0.70d,
                    0.82d,
                    0.62d,
                    0.74d,
                    0.35d,
                    0.18d,
                    true,
                    List.of("contradicts-error-code-guide"),
                    2300
                ),
                Map.of()
            ),
            memoryCandidate(
                "mem-pay-401-evidence",
                "Tenant bootstrap explains PAY_401",
                "PAY_401 failure resolved after tenant bootstrap restored payment auth state.",
                2,
                routes(
                    0.83d,
                    List.of("qv-failure-memory", "qv-pay-401-graph"),
                    new RerankMatchedRoute("semantic-memory", 1, 0.83d),
                    new RerankMatchedRoute("graph-memory", 1, 0.88d)
                ),
                memoryLedger(
                    0.78d,
                    0.78d,
                    0.55d,
                    0.78d,
                    0.66d,
                    0.56d,
                    0.72d,
                    0.92d,
                    0.84d,
                    0.82d,
                    0.86d,
                    2,
                    false,
                    List.of(),
                    92
                ),
                memoryMetadata()
            ),
            knowledgeCandidate(
                "err-pay-401-cause",
                "PAY_401 current error code entry",
                "PAY_401 means tenant bootstrap did not prepare payment authorization.",
                3,
                routes(
                    0.76d,
                    List.of("qv-failure-error-code", "qv-pay-api"),
                    new RerankMatchedRoute("metadata-exact", 1, 0.98d),
                    new RerankMatchedRoute("rewritten-semantic", 2, 0.86d),
                    new RerankMatchedRoute("document-type:error_code", 1, 0.91d)
                ),
                knowledgeLedger(
                    0.98d,
                    0.91d,
                    1.0d,
                    0.86d,
                    0.90d,
                    0.78d,
                    1.0d,
                    0.96d,
                    false,
                    List.of(),
                    44
                ),
                Map.of("scenario", "failure_analysis", "parentIdentity", "error_code:PAY_401")
            )
        );
    }

    private List<KnowledgeExpansionSource> knowledgeExpansionSources() {
        return List.of(
            source(
                "spec-pagination-max",
                "doc-test-spec",
                "rev-test-spec-v5",
                DocumentType.TEST_SPEC,
                "Page size maximum",
                "When pageSize is above 200 the API must reject the request.",
                "wiki/test-spec.md#page-size-max",
                "test_spec:pagination-boundary",
                "Pagination boundary rules",
                "pagination-boundary",
                "OrderSearch",
                "",
                10,
                42
            ),
            source(
                "spec-pagination-min",
                "doc-test-spec",
                "rev-test-spec-v5",
                DocumentType.TEST_SPEC,
                "Page size minimum",
                "When pageSize is below 1 the API must reject the request.",
                "wiki/test-spec.md#page-size-min",
                "test_spec:pagination-boundary",
                "Pagination boundary rules",
                "pagination-boundary",
                "OrderSearch",
                "",
                11,
                38
            ),
            source(
                "err-pay-401-cause",
                "doc-payment-errors",
                "rev-errors-v3",
                DocumentType.ERROR_CODE_GUIDE,
                "PAY_401 cause",
                "PAY_401 is raised when tenant bootstrap did not prepare payment auth.",
                "wiki/payment-errors.md#pay-401-cause",
                "error_code:PAY_401",
                "Payment error codes",
                "PAY_401",
                "Payment",
                "",
                20,
                44
            ),
            source(
                "err-pay-401-action",
                "doc-payment-errors",
                "rev-errors-v3",
                DocumentType.ERROR_CODE_GUIDE,
                "PAY_401 handling",
                "Restore tenant bootstrap before retrying payment authorization.",
                "wiki/payment-errors.md#pay-401-action",
                "error_code:PAY_401",
                "Payment error codes",
                "PAY_401",
                "Payment",
                "",
                21,
                40
            )
        );
    }

    private RerankCandidate knowledgeCandidate(
        String id,
        String title,
        String content,
        int beforeRank,
        RerankRouteEvidence routeEvidence,
        RerankFeatureLedger ledger,
        Map<String, Object> metadata
    ) {
        return candidate(
            RerankCorpusType.KNOWLEDGE,
            id,
            title,
            content,
            beforeRank,
            routeEvidence,
            ledger,
            new RerankSourceIdentity("KNOWLEDGE_CHUNK", "fixture/" + id, Map.of("chunkId", id)),
            metadata
        );
    }

    private RerankCandidate memoryCandidate(
        String id,
        String title,
        String content,
        int beforeRank,
        RerankRouteEvidence routeEvidence,
        RerankFeatureLedger ledger,
        Map<String, Object> metadata
    ) {
        return candidate(
            RerankCorpusType.MEMORY,
            id,
            title,
            content,
            beforeRank,
            routeEvidence,
            ledger,
            new RerankSourceIdentity("FAILURE_PATTERN", "ltm/" + id, Map.of(
                "memoryId", id,
                "factFingerprint", metadata.getOrDefault("factFingerprint", "fact-" + id).toString()
            )),
            metadata
        );
    }

    private RerankCandidate candidate(
        RerankCorpusType corpusType,
        String id,
        String title,
        String content,
        int beforeRank,
        RerankRouteEvidence routeEvidence,
        RerankFeatureLedger ledger,
        RerankSourceIdentity sourceIdentity,
        Map<String, Object> metadata
    ) {
        return new RerankCandidate(
            corpusType,
            new RerankCandidateIdentity(corpusType, id),
            sourceIdentity,
            title,
            content,
            beforeRank,
            routeEvidence.fusedScore(),
            routeEvidence,
            ledger,
            metadata,
            ledger.tokenCost(),
            List.of()
        );
    }

    private RerankRouteEvidence routes(
        double fusedScore,
        List<String> queryVariants,
        RerankMatchedRoute... matchedRoutes
    ) {
        return new RerankRouteEvidence(queryVariants, List.of(matchedRoutes), Map.of(), Map.of(), fusedScore);
    }

    private RerankFeatureLedger knowledgeLedger(
        Double stageFit,
        Double routeAgreement,
        Double exactEntity,
        Double semantic,
        Double metadata,
        Double lexical,
        Double authority,
        Double freshness,
        boolean lowConfidence,
        List<String> conflictSignals,
        int tokenCost
    ) {
        return new RerankFeatureLedger(
            semantic,
            metadata,
            lexical,
            routeAgreement,
            exactEntity,
            stageFit,
            authority,
            freshness,
            null,
            null,
            null,
            null,
            null,
            lowConfidence,
            conflictSignals,
            tokenCost,
            List.of()
        );
    }

    private RerankFeatureLedger memoryLedger(
        Double stageFit,
        Double routeAgreement,
        Double exactEntity,
        Double semantic,
        Double metadata,
        Double lexical,
        Double freshness,
        Double memoryConfidence,
        Double memoryImportance,
        Double memorySuccessContribution,
        Double graphConfidence,
        Integer graphPathLength,
        boolean lowConfidence,
        List<String> conflictSignals,
        int tokenCost
    ) {
        return new RerankFeatureLedger(
            semantic,
            metadata,
            lexical,
            routeAgreement,
            exactEntity,
            stageFit,
            null,
            freshness,
            memoryConfidence,
            memoryImportance,
            memorySuccessContribution,
            graphConfidence,
            graphPathLength,
            lowConfidence,
            conflictSignals,
            tokenCost,
            List.of()
        );
    }

    private KnowledgeExpansionSource source(
        String chunkId,
        String documentId,
        String documentRevisionId,
        DocumentType documentType,
        String title,
        String content,
        String sourceRef,
        String parentIdentity,
        String heading,
        String entryKey,
        String businessEntity,
        String flowId,
        int chunkOrder,
        int tokenCost
    ) {
        return new KnowledgeExpansionSource(
            chunkId,
            documentId,
            documentRevisionId,
            documentType,
            title,
            content,
            sourceRef,
            parentIdentity,
            heading,
            entryKey,
            businessEntity,
            flowId,
            chunkOrder,
            tokenCost,
            KnowledgeExpansionSourceStatus.ACTIVE,
            true,
            Map.of(
                "parentIdentity",
                parentIdentity,
                "heading",
                heading,
                "entryKey",
                entryKey,
                "businessEntity",
                businessEntity
            )
        );
    }

    private Map<String, Object> memoryMetadata() {
        return Map.ofEntries(
            Map.entry("factFingerprint", "fact-pay-401-bootstrap"),
            Map.entry("sanitizedFullContent", "PAY_401 failure resolved after tenant bootstrap. token=secret-value"),
            Map.entry("evidenceSummaries", List.of(
                "PAY_401 reproduced before tenant bootstrap",
                "PAY_401 passed after tenant bootstrap restored auth state"
            )),
            Map.entry("sourceRefs", List.of("suite-runs/42", "suite-runs/43")),
            Map.entry("mergedSourceRefs", List.of("merged/payment-auth-bootstrap")),
            Map.entry("evidenceCount", 2),
            Map.entry("systemName", "order-platform"),
            Map.entry("moduleName", "payment"),
            Map.entry("apiPath", "/api/orders/{orderId}/pay"),
            Map.entry("errorCode", "PAY_401"),
            Map.entry("businessEntity", "Order"),
            Map.entry("graphRelationPath", List.of("ERROR_CODE:PAY_401", "API_PATH:/api/orders/{orderId}/pay")),
            Map.entry("graphRelationConfidence", 0.89d),
            Map.entry("graphSourceMemoryIds", List.of("mem-pay-401-evidence", "mem-tenant-bootstrap")),
            Map.entry("graphSourceRefs", List.of("ltm/pay-401", "ltm/tenant-bootstrap")),
            Map.entry("graphFactFingerprints", List.of("fact-pay-401-bootstrap", "fact-tenant-bootstrap")),
            Map.entry("graphEvidenceSummaries", List.of(
                "PAY_401 and tenant bootstrap co-occurred across regression suites"
            ))
        );
    }

    private RerankOutputItem itemById(RerankOutput output, String stableKey) {
        return output.items().stream()
            .filter(item -> item.candidate().candidateIdentity().stableKey().equals(stableKey))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing fixture item " + stableKey));
    }

    private KnowledgeExpansionContext knowledgeContext(KnowledgeExpansionResult result, String anchorChunkId) {
        return result.contexts().stream()
            .filter(context -> context.anchorChunkId().equals(anchorChunkId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing expanded knowledge context " + anchorChunkId));
    }

    private String stableKey(RerankCorpusType corpusType, String candidateId) {
        return RerankCandidateIdentity.stableKey(corpusType, candidateId);
    }

    private record ScenarioEvaluation(
        String stage,
        RerankOutput output,
        RerankOutputItem promoted,
        RerankOutputItem demoted
    ) {
        private String promotedExplanation() {
            return "stage=" + stage
                + " promoted " + promoted.candidate().candidateIdentity().stableKey()
                + " from V5-4 fused rank=" + promoted.beforeRank()
                + " to V5-5 post-rerank rank=" + promoted.afterRank()
                + " because " + promoted.reasons()
                + " :: " + promoted.scoreExplanation();
        }

        private String demotedExplanation() {
            return "stage=" + stage
                + " demoted " + demoted.candidate().candidateIdentity().stableKey()
                + " from V5-4 fused rank=" + demoted.beforeRank()
                + " to V5-5 post-rerank rank=" + demoted.afterRank()
                + " because penalties=" + demoted.penalties()
                + " :: " + demoted.scoreExplanation();
        }
    }
}
