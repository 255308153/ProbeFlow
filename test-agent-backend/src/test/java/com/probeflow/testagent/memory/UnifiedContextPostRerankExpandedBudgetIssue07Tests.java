package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.apispec.ApiSpec;
import com.probeflow.testagent.apispec.ApiSpecRepository;
import com.probeflow.testagent.apispec.ApiSpecSourceType;
import com.probeflow.testagent.apispec.HttpMethod;
import com.probeflow.testagent.knowledge.DocumentAuthority;
import com.probeflow.testagent.knowledge.DocumentType;
import com.probeflow.testagent.knowledge.KnowledgeContext;
import com.probeflow.testagent.knowledge.KnowledgeContextEntry;
import com.probeflow.testagent.knowledge.KnowledgeQuery;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalApplicationService;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalHit;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalResult;
import com.probeflow.testagent.rerank.KnowledgeExpansionCitation;
import com.probeflow.testagent.rerank.KnowledgeExpansionCitationRole;
import com.probeflow.testagent.rerank.KnowledgeExpansionContext;
import com.probeflow.testagent.rerank.KnowledgeExpansionReason;
import com.probeflow.testagent.rerank.KnowledgeExpansionResult;
import com.probeflow.testagent.rerank.KnowledgeExpansionSource;
import com.probeflow.testagent.rerank.MemoryConflictAuditEvidence;
import com.probeflow.testagent.rerank.MemoryEvidenceAnchor;
import com.probeflow.testagent.rerank.MemoryEvidenceAnchorType;
import com.probeflow.testagent.rerank.MemoryEvidenceCitation;
import com.probeflow.testagent.rerank.MemoryEvidenceExpansionItem;
import com.probeflow.testagent.rerank.MemoryEvidenceExpansionResult;
import com.probeflow.testagent.rerank.MemoryEvidenceIdentityHints;
import com.probeflow.testagent.rerank.MemoryEvidenceRole;
import com.probeflow.testagent.rerank.MemoryGraphRelationExpansion;
import com.probeflow.testagent.rerank.RerankCandidate;
import com.probeflow.testagent.rerank.RerankCandidateIdentity;
import com.probeflow.testagent.rerank.RerankCorpusType;
import com.probeflow.testagent.rerank.RerankFeatureLedger;
import com.probeflow.testagent.rerank.RerankMatchedRoute;
import com.probeflow.testagent.rerank.RerankOutput;
import com.probeflow.testagent.rerank.RerankOutputItem;
import com.probeflow.testagent.rerank.RerankRouteEvidence;
import com.probeflow.testagent.rerank.RerankSourceIdentity;
import com.probeflow.testagent.task.TaskRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UnifiedContextPostRerankExpandedBudgetIssue07Tests {

    @Mock
    private TaskRepository tasks;

    @Mock
    private ApiSpecRepository apiSpecs;

    @Mock
    private SessionMemoryService sessionMemoryService;

    @Mock
    private TaskMemoryService taskMemoryService;

    @Mock
    private KnowledgeRetrievalApplicationService knowledgeRetrieval;

    @Mock
    private LongTermMemoryRetrievalService longTermMemoryRetrieval;

    @Mock
    private MemoryUsageRecordingService memoryUsageRecording;

    @Test
    void postRerankExpandedMaterialsDriveUnifiedBudgetAndCitationEvidence() {
        var criticalKnowledge = knowledgeContext(
            "chunk-critical-07",
            "error_code:PAY_401",
            KnowledgeExpansionReason.ERROR_CODE_ENTRY,
            10,
            source("chunk-critical-07", "PAY_401 handling", "Bootstrap tenant before retrying payment auth.", 4),
            source("chunk-critical-action-07", "PAY_401 action", "Retry only after tenant bootstrap evidence is present.", 6)
        );
        var lowRankKnowledge = knowledgeContext(
            "chunk-low-rank-07",
            "api_note:general-payment",
            KnowledgeExpansionReason.PARENT_SECTION,
            26,
            source("chunk-low-rank-07", "General payment note", "Tenant bootstrap is not required for payment auth.", 26)
        );
        var memory = memoryItem("mem-pay-401-07", 8, true);
        var postRerank = new PostRerankExpandedContext(
            new RerankOutput(List.of(
                output(knowledgeCandidate("chunk-low-rank-07", 1), 1, 3, List.of("weak-stage-fit"), List.of("high-token-cost")),
                output(knowledgeCandidate("chunk-critical-07", 4), 4, 1, List.of("exact-entity", "route-agreement"), List.of()),
                output(memoryCandidate("mem-pay-401-07", 2, true), 2, 2, List.of("graph-relation-evidence"), List.of("conflict-audit"))
            )),
            new KnowledgeExpansionResult(List.of(lowRankKnowledge, criticalKnowledge), 40, 36, false),
            new MemoryEvidenceExpansionResult(List.of(memory), 40, 8, false, List.of()),
            List.of("post-rerank-expanded-context")
        );

        var bundle = builder().build(query(24, postRerank));

        assertThat(bundle.knowledgeContext().citedChunks())
            .extracting(KnowledgeContextEntry::chunkId)
            .containsExactly("chunk-critical-07")
            .doesNotContain("chunk-low-rank-07");
        assertThat(bundle.longTermMemoryContext().hits())
            .extracting(LongTermMemoryRetrievalHit::memoryId)
            .containsExactly("mem-pay-401-07");
        assertThat(bundle.citations())
            .extracting(ContextCitation::sourceId)
            .contains("chunk-critical-07", "mem-pay-401-07")
            .doesNotContain("chunk-low-rank-07");
        assertThat(bundle.budget().knowledgeTokens()).isEqualTo(10);
        assertThat(bundle.budget().longTermMemoryTokens()).isEqualTo(8);
        assertThat(bundle.budget().totalEstimatedTokens()).isLessThanOrEqualTo(bundle.budget().requestedTokenBudget());
        assertThat(bundle.budget().originalEstimatedTokens()).isGreaterThan(bundle.budget().requestedTokenBudget());
        assertThat(bundle.budget().pruned()).isTrue();
        assertThat(bundle.conflicts()).isEmpty();

        var knowledgeEvidence = citationEvidence(bundle, "chunk-critical-07");
        assertThat(knowledgeEvidence)
            .containsEntry("postRerank", true)
            .containsEntry("queryVariantIds", List.of("qv-original-07", "qv-error-07"))
            .containsEntry("routeNames", List.of("metadata-exact", "rewritten-semantic"))
            .containsEntry("preRerankRank", 4)
            .containsEntry("postRerankRank", 1)
            .containsEntry("rerankReasons", List.of("exact-entity", "route-agreement"))
            .containsEntry("rerankPenalties", List.of())
            .containsEntry("smallToBigAnchor", "chunk-critical-07")
            .containsEntry("parentIdentity", "error_code:PAY_401")
            .containsEntry("expansionReason", "ERROR_CODE_ENTRY")
            .containsEntry("expansionTokenCost", 10);
        assertThat(knowledgeEvidence.get("expandedSources"))
            .asList()
            .hasSize(2);

        var memoryEvidence = citationEvidence(bundle, "mem-pay-401-07");
        assertThat(memoryEvidence)
            .containsEntry("postRerank", true)
            .containsEntry("preRerankRank", 2)
            .containsEntry("postRerankRank", 2)
            .containsEntry("rerankReasons", List.of("graph-relation-evidence"))
            .containsEntry("rerankPenalties", List.of("conflict-audit"))
            .containsEntry("smallToBigAnchor", "mem-pay-401-07")
            .containsEntry("expansionTokenCost", 8)
            .containsEntry("lowConfidence", true)
            .containsEntry("positiveRecommendationEligible", false);
        assertThat(memoryEvidence.get("conflictAudit"))
            .asList()
            .singleElement()
            .satisfies(audit -> assertThat(audit)
                .isEqualTo(Map.of(
                    "role", "AUDIT",
                    "summary", "old runbook recommended retry without bootstrap",
                    "sourceRefs", List.of("runbook/old-pay-401")
                )));
    }

    @Test
    void deduplicatesParentExpansionsAndMemoryEvidenceBeforeBuildingContext() {
        var firstParent = knowledgeContext(
            "chunk-parent-first-07",
            "test_spec:pagination",
            KnowledgeExpansionReason.TEST_SPEC_RULE_GROUP,
            8,
            source("chunk-parent-first-07", "Page size max", "Reject pageSize above the limit.", 8)
        );
        var duplicateParent = knowledgeContext(
            "chunk-parent-duplicate-07",
            "test_spec:pagination",
            KnowledgeExpansionReason.TEST_SPEC_RULE_GROUP,
            7,
            source("chunk-parent-duplicate-07", "Page size min", "Reject pageSize below the limit.", 7)
        );
        var firstMemory = memoryItem("mem-duplicate-07", 6, false);
        var duplicateMemory = memoryItem("mem-duplicate-07", 6, false);
        var postRerank = new PostRerankExpandedContext(
            new RerankOutput(List.of(
                output(knowledgeCandidate("chunk-parent-first-07", 1), 1, 1, List.of("first-parent"), List.of()),
                output(knowledgeCandidate("chunk-parent-duplicate-07", 2), 2, 2, List.of("same-parent"), List.of()),
                output(memoryCandidate("mem-duplicate-07", 3, false), 3, 3, List.of("first-memory-evidence"), List.of())
            )),
            new KnowledgeExpansionResult(List.of(firstParent, duplicateParent), 100, 15, false),
            new MemoryEvidenceExpansionResult(List.of(firstMemory, duplicateMemory), 100, 12, false, List.of()),
            List.of()
        );

        var bundle = builder().build(query(80, postRerank));

        assertThat(bundle.knowledgeContext().citedChunks())
            .extracting(KnowledgeContextEntry::chunkId)
            .containsExactly("chunk-parent-first-07");
        assertThat(bundle.longTermMemoryContext().hits())
            .extracting(LongTermMemoryRetrievalHit::memoryId)
            .containsExactly("mem-duplicate-07");
        assertThat(bundle.citations())
            .extracting(ContextCitation::sourceId)
            .containsExactly("chunk-parent-first-07", "mem-duplicate-07");
    }

    @Test
    void emptyPostRerankExpansionFallsBackToV54RetrievalPath() {
        var fallbackEntry = new KnowledgeContextEntry(
            "chunk-v54-07",
            "doc-v54-07",
            "rev-v54-07",
            "V5-4 fallback note",
            0.81d,
            "api_note",
            "wiki/v54.md",
            Map.of(),
            List.of("v5-4-route"),
            false
        );
        when(knowledgeRetrieval.retrieve(any(KnowledgeQuery.class)))
            .thenReturn(new KnowledgeRetrievalResult(
                "PAY_401",
                List.of(new KnowledgeRetrievalHit(
                    "chunk-v54-07",
                    "doc-v54-07",
                    "rev-v54-07",
                    "V5-4 fallback note",
                    "V5-4 fallback keeps original retrieval behavior.",
                    "wiki/v54.md",
                    DocumentType.API_NOTE,
                    DocumentAuthority.HIGH,
                    "billing",
                    "payment",
                    "Order",
                    List.of("payment"),
                    List.of("failure_analysis"),
                    Map.of(),
                    5,
                    0.81d,
                    Map.of(),
                    List.of("v5-4-route"),
                    false
                )),
                new KnowledgeContext(List.of(), List.of(fallbackEntry), List.of(), List.of(), List.of(), List.of(), List.of(fallbackEntry), false, false),
                0.8d,
                1,
                5,
                false
            ));
        when(longTermMemoryRetrieval.retrieve(any(LongTermMemoryQuery.class)))
            .thenReturn(new LongTermMemoryRetrievalResult(List.of(), 0, 0));
        var emptyPostRerank = new PostRerankExpandedContext(
            new RerankOutput(List.of()),
            new KnowledgeExpansionResult(List.of(), 0, 0, false),
            new MemoryEvidenceExpansionResult(List.of(), 0, 0, false, List.of()),
            List.of("post-rerank-empty")
        );

        var bundle = builder().build(query(80, emptyPostRerank));

        assertThat(bundle.knowledgeContext().apiNotes())
            .extracting(KnowledgeContextEntry::chunkId)
            .containsExactly("chunk-v54-07");
        assertThat(citationEvidence(bundle, "chunk-v54-07"))
            .doesNotContainKeys("postRerank", "preRerankRank", "postRerankRank", "smallToBigAnchor");
        assertThat(retrievalDiagnostics(bundle))
            .containsEntry("postRerank", List.of(
                "post-rerank-empty",
                "post-rerank-expanded-context-fallback",
                "post-rerank-output-missing",
                "post-rerank-expansion-missing"
            ));
        verify(knowledgeRetrieval).retrieve(any(KnowledgeQuery.class));
        verify(longTermMemoryRetrieval).retrieve(any(LongTermMemoryQuery.class));
    }

    @Test
    void postRerankContextDoesNotCreateAnotherLongTermMemoryWriteEntry() throws Exception {
        var backendRoot = Path.of("").toAbsolutePath();
        var unifiedContextBuilderSource = Files.readString(backendRoot.resolve(
            "src/main/java/com/probeflow/testagent/memory/UnifiedContextBuilder.java"
        ));
        var rerankSources = sourceText(backendRoot.resolve("src/main/java/com/probeflow/testagent/rerank"));
        var feedbackSource = Files.readString(backendRoot.resolve(
            "src/main/java/com/probeflow/testagent/agentmemoryfeedback/AgentMemoryFeedbackApplicationService.java"
        ));
        var refinerySource = Files.readString(backendRoot.resolve(
            "src/main/java/com/probeflow/testagent/memory/MemoryRefineryService.java"
        ));

        assertThat(unifiedContextBuilderSource)
            .contains("recordLongTermMemoryUsage")
            .doesNotContain("new LongTermMemory(")
            .doesNotContain("MemoryRefineryService");
        assertThat(rerankSources)
            .doesNotContain("new LongTermMemory(")
            .doesNotContain("MemoryRefineryService");
        assertThat(feedbackSource).contains("MemoryRefineryService");
        assertThat(refinerySource)
            .contains("new LongTermMemory()")
            .contains("longTermMemories.save(memory)");
    }

    private UnifiedContextBuilder builder() {
        return new UnifiedContextBuilder(
            tasks,
            apiSpecs,
            sessionMemoryService,
            taskMemoryService,
            knowledgeRetrieval,
            longTermMemoryRetrieval,
            memoryUsageRecording
        );
    }

    private UnifiedContextQuery query(int tokenBudget, PostRerankExpandedContext postRerank) {
        return new UnifiedContextQuery(
            null,
            null,
            null,
            tinyApiSpec(),
            "failure_analysis",
            "PAY_401 payment auth tenant bootstrap",
            null,
            null,
            null,
            "PAY_401",
            List.of("payment", "auth"),
            tokenBudget,
            null,
            null,
            postRerank
        );
    }

    private KnowledgeExpansionContext knowledgeContext(
        String anchorChunkId,
        String parentIdentity,
        KnowledgeExpansionReason reason,
        int tokenCost,
        KnowledgeExpansionSource... sources
    ) {
        return new KnowledgeExpansionContext(
            anchorChunkId,
            parentIdentity,
            reason,
            "rev-07",
            tokenCost,
            List.of(sources),
            List.of(),
            List.of(
                new KnowledgeExpansionCitation(anchorChunkId, "rev-07", "wiki/anchor-07.md", KnowledgeExpansionCitationRole.ANCHOR)
            )
        );
    }

    private KnowledgeExpansionSource source(String chunkId, String title, String content, int tokenCost) {
        return new KnowledgeExpansionSource(
            chunkId,
            "doc-" + chunkId,
            "rev-07",
            DocumentType.ERROR_CODE_GUIDE,
            title,
            content,
            "wiki/" + chunkId + ".md",
            "parent-" + chunkId,
            "PAY_401",
            "PAY_401",
            "Payment",
            null,
            1,
            tokenCost,
            null,
            true,
            Map.of("errorCode", "PAY_401")
        );
    }

    private MemoryEvidenceExpansionItem memoryItem(String memoryId, int tokens, boolean lowConfidence) {
        return new MemoryEvidenceExpansionItem(
            new MemoryEvidenceAnchor(MemoryEvidenceAnchorType.GRAPH_RELATION, memoryId, "fact-" + memoryId, List.of("ERROR_CODE:PAY_401", "API_PATH:/pay")),
            "PAY_401 tenant bootstrap memory",
            "PAY_401 passed after tenant bootstrap was restored.",
            List.of("PAY_401 requires tenant bootstrap for auth", "suite run 43 passed with bootstrap restored"),
            List.of("ltm/" + memoryId),
            List.of("merged/payment-auth-bootstrap"),
            2,
            new MemoryEvidenceIdentityHints("order-platform", "payment", "/pay", "PAY_401", "Order", null, null, null),
            new MemoryGraphRelationExpansion(
                List.of("ERROR_CODE:PAY_401", "API_PATH:/pay"),
                0.87d,
                List.of(memoryId),
                List.of("ltm/" + memoryId),
                List.of("fact-" + memoryId),
                "PAY_401 co-occurred with tenant bootstrap failures.",
                "Graph relation ties PAY_401 to tenant bootstrap."
            ),
            List.of(new MemoryConflictAuditEvidence(
                MemoryEvidenceRole.AUDIT,
                "old runbook recommended retry without bootstrap",
                List.of("runbook/old-pay-401")
            )),
            !lowConfidence,
            lowConfidence,
            lowConfidence ? "graph relation is based on limited evidence" : null,
            tokens,
            List.of(),
            List.of(new MemoryEvidenceCitation(memoryId, "fact-" + memoryId, "ltm/" + memoryId, MemoryEvidenceRole.GRAPH_RELATION))
        );
    }

    private RerankOutputItem output(
        RerankCandidate candidate,
        int beforeRank,
        int afterRank,
        List<String> reasons,
        List<String> penalties
    ) {
        return new RerankOutputItem(candidate, beforeRank, afterRank, 0.92d - (afterRank * 0.01d), "deterministic fixture", reasons, penalties);
    }

    private RerankCandidate knowledgeCandidate(String chunkId, int beforeRank) {
        return new RerankCandidate(
            RerankCorpusType.KNOWLEDGE,
            new RerankCandidateIdentity(RerankCorpusType.KNOWLEDGE, chunkId),
            new RerankSourceIdentity("ERROR_CODE_GUIDE", "wiki/" + chunkId + ".md", Map.of(
                "chunkId", chunkId,
                "documentId", "doc-" + chunkId,
                "documentRevisionId", "rev-07"
            )),
            "PAY_401 knowledge " + chunkId,
            "PAY_401 knowledge requires tenant bootstrap.",
            beforeRank,
            0.84d,
            routeEvidence(),
            ledger(null, false, List.of(), 10),
            Map.of("errorCode", "PAY_401"),
            10,
            List.of()
        );
    }

    private RerankCandidate memoryCandidate(String memoryId, int beforeRank, boolean lowConfidence) {
        return new RerankCandidate(
            RerankCorpusType.MEMORY,
            new RerankCandidateIdentity(RerankCorpusType.MEMORY, memoryId),
            new RerankSourceIdentity("FAILURE_PATTERN", "ltm/" + memoryId, Map.of(
                "memoryId", memoryId,
                "factFingerprint", "fact-" + memoryId
            )),
            "PAY_401 memory " + memoryId,
            "PAY_401 memory says tenant bootstrap is required.",
            beforeRank,
            0.86d,
            routeEvidence(),
            ledger(0.62d, lowConfidence, List.of("contradicts-old-runbook"), 8),
            Map.of(
                "factFingerprint", "fact-" + memoryId,
                "scopeType", "FAILURE_PATTERN",
                "sourceType", "MEMORY_REFINERY",
                "errorCode", "PAY_401"
            ),
            8,
            List.of()
        );
    }

    private RerankRouteEvidence routeEvidence() {
        return new RerankRouteEvidence(
            List.of("qv-original-07", "qv-error-07"),
            List.of(
                new RerankMatchedRoute("metadata-exact", 1, 0.98d),
                new RerankMatchedRoute("rewritten-semantic", 2, 0.80d)
            ),
            Map.of("metadata-exact", 1, "rewritten-semantic", 2),
            Map.of("metadata-exact", 0.98d, "rewritten-semantic", 0.80d),
            0.88d
        );
    }

    private RerankFeatureLedger ledger(
        Double memoryConfidence,
        boolean lowConfidence,
        List<String> conflictSignals,
        int tokenCost
    ) {
        return new RerankFeatureLedger(
            0.82d,
            0.74d,
            0.61d,
            0.90d,
            1.0d,
            0.94d,
            1.0d,
            0.80d,
            memoryConfidence,
            0.77d,
            0.71d,
            0.87d,
            2,
            lowConfidence,
            conflictSignals,
            tokenCost,
            List.of()
        );
    }

    private ApiSpec tinyApiSpec() {
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId("api-issue-07");
        apiSpec.setSystemName("billing");
        apiSpec.setModuleName("payment");
        apiSpec.setHttpMethod(HttpMethod.POST);
        apiSpec.setPath("/p");
        apiSpec.setSummary("P");
        apiSpec.setDescription(null);
        apiSpec.setOperationId("p");
        apiSpec.setParameters(Map.of());
        apiSpec.setConstraints(Map.of());
        apiSpec.setAuth(Map.of());
        apiSpec.setSourceType(ApiSpecSourceType.OPENAPI);
        apiSpec.setSourceRef("issue-07-openapi.yaml");
        return apiSpec;
    }

    private Map<String, Object> citationEvidence(ContextBundle bundle, String sourceId) {
        return bundle.citations().stream()
            .filter(citation -> sourceId.equals(citation.sourceId()))
            .findFirst()
            .orElseThrow()
            .evidence();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> retrievalDiagnostics(ContextBundle bundle) {
        return (Map<String, Object>) bundle.constraints().get("retrievalDiagnostics");
    }

    private String sourceText(Path sourceRoot) throws Exception {
        try (var paths = Files.walk(sourceRoot)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .map(this::readUnchecked)
                .collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
