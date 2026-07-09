package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.probeflow.testagent.knowledge.KnowledgeRouteEvidence;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalApplicationService;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalHit;
import com.probeflow.testagent.knowledge.KnowledgeRetrievalResult;
import com.probeflow.testagent.retrieval.RetrievalRouteDiagnostic;
import com.probeflow.testagent.retrieval.RetrievalRouteEvidence;
import com.probeflow.testagent.task.TaskRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UnifiedContextMultiRouteEvidenceIssue05Tests {

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
    void citationsExposeKnowledgeAndMemoryMultiRouteEvidenceWithoutDuplicatingSources() {
        var knowledgeHit = knowledgeHit(
            "chunk-05",
            9,
            0.94d,
            Map.of(
                "fusedScore", 0.94d,
                "fusionExplanation", Map.of(
                    "formula", "bestWeightedRouteScore + rankContributionTotal + routeAgreementBonus",
                    "routeCount", 2
                )
            ),
            List.of(
                new KnowledgeRouteEvidence("original-semantic", "qv-05-original", "ERROR_ANALYSIS", 1, 0.93d, "semantic query matched PAY_401"),
                new KnowledgeRouteEvidence("metadata-exact", "qv-05-error", "ERROR_CODE", 2, 0.88d, "error code metadata matched PAY_401")
            )
        );
        var memoryHit = memoryHit(
            "memory-graph-05",
            11,
            0.89d,
            Map.of(
                "retrievalChannel", "graph",
                "graphMatchReason", "graph-related-error-code",
                "graphRelationPath", List.of("ERROR_OBSERVED_ON_API", "ENTITY_RELATED_TO_MEMORY"),
                "graphRelationConfidence", 0.83d,
                "graphSourceMemoryIds", List.of("memory-seed-05", "memory-graph-05"),
                "graphEvidenceSummaries", List.of("PAY_401 was observed on payment auth."),
                "fusedScore", 0.89d,
                "fusionExplanation", Map.of(
                    "formula", "bestWeightedRouteScore + rankContributionTotal + routeAgreementBonus",
                    "routeCount", 2
                )
            ),
            List.of(
                new RetrievalRouteEvidence(
                    "semantic_memory",
                    "qv-05-memory-semantic",
                    "FAILURE_ANALYSIS",
                    1,
                    0.86d,
                    "semantic memory matched tenant bootstrap",
                    Map.of("memoryId", "memory-graph-05")
                ),
                new RetrievalRouteEvidence(
                    "graph_memory",
                    "qv-05-memory-graph",
                    "ERROR_CODE",
                    2,
                    0.81d,
                    "graph related PAY_401 to payment auth memory",
                    Map.of(
                        "graphRelationPath", List.of("ERROR_OBSERVED_ON_API", "ENTITY_RELATED_TO_MEMORY"),
                        "graphRelationConfidence", 0.83d,
                        "graphSourceMemoryIds", List.of("memory-seed-05", "memory-graph-05"),
                        "graphEvidenceSummaries", List.of("PAY_401 was observed on payment auth.")
                    )
                )
            )
        );

        when(knowledgeRetrieval.retrieve(any(KnowledgeQuery.class)))
            .thenReturn(knowledgeResult(List.of(knowledgeHit), 1.0d, false, List.of()));
        when(longTermMemoryRetrieval.retrieve(any(LongTermMemoryQuery.class)))
            .thenReturn(new LongTermMemoryRetrievalResult(List.of(memoryHit), 1, 11));

        var bundle = builder().build(query(80));

        assertThat(bundle.citations())
            .filteredOn(citation -> "chunk-05".equals(citation.sourceId()))
            .hasSize(1);
        assertThat(bundle.citations())
            .filteredOn(citation -> "memory-graph-05".equals(citation.sourceId()))
            .hasSize(1);

        var knowledgeEvidence = citationEvidence(bundle, "chunk-05");
        assertThat(knowledgeEvidence)
            .containsEntry("queryVariantIds", List.of("qv-05-original", "qv-05-error"))
            .containsEntry("queryVariantIntents", List.of("ERROR_ANALYSIS", "ERROR_CODE"))
            .containsEntry("routeNames", List.of("original-semantic", "metadata-exact"))
            .containsEntry("routeMatchReasons", List.of("semantic query matched PAY_401", "error code metadata matched PAY_401"))
            .containsEntry("routeRanks", Map.of(
                "original-semantic:qv-05-original", 1,
                "metadata-exact:qv-05-error", 2
            ))
            .containsEntry("fusedScore", 0.94d);
        assertThat(knowledgeEvidence.get("queryVariantIntentById"))
            .isEqualTo(Map.of("qv-05-original", "ERROR_ANALYSIS", "qv-05-error", "ERROR_CODE"));
        assertThat(knowledgeEvidence.get("fusionExplanation"))
            .isEqualTo(Map.of(
                "formula", "bestWeightedRouteScore + rankContributionTotal + routeAgreementBonus",
                "routeCount", 2
            ));
        assertThat(routeEvidence(knowledgeEvidence))
            .anySatisfy(route -> assertThat(route)
                .containsEntry("routeName", "original-semantic")
                .containsEntry("queryVariantId", "qv-05-original")
                .containsEntry("queryIntent", "ERROR_ANALYSIS")
                .containsEntry("routeRank", 1)
                .containsEntry("matchReason", "semantic query matched PAY_401"));

        var memoryEvidence = citationEvidence(bundle, "memory-graph-05");
        assertThat(memoryEvidence)
            .containsEntry("retrievalChannel", "graph")
            .containsEntry("graphRelationPath", List.of("ERROR_OBSERVED_ON_API", "ENTITY_RELATED_TO_MEMORY"))
            .containsEntry("graphRelationConfidence", 0.83d)
            .containsEntry("graphSourceMemoryIds", List.of("memory-seed-05", "memory-graph-05"))
            .containsEntry("graphEvidenceSummaries", List.of("PAY_401 was observed on payment auth."))
            .containsEntry("queryVariantIds", List.of("qv-05-memory-semantic", "qv-05-memory-graph"))
            .containsEntry("queryVariantIntents", List.of("FAILURE_ANALYSIS", "ERROR_CODE"))
            .containsEntry("routeNames", List.of("semantic_memory", "graph_memory"))
            .containsEntry("routeMatchReasons", List.of(
                "semantic memory matched tenant bootstrap",
                "graph related PAY_401 to payment auth memory"
            ))
            .containsEntry("fusedScore", 0.89d);
        assertThat(memoryEvidence.get("queryVariantIntentById"))
            .isEqualTo(Map.of("qv-05-memory-semantic", "FAILURE_ANALYSIS", "qv-05-memory-graph", "ERROR_CODE"));
        assertThat(routeEvidence(memoryEvidence))
            .anySatisfy(route -> {
                assertThat(route)
                    .containsEntry("routeName", "graph_memory")
                    .containsEntry("queryVariantId", "qv-05-memory-graph")
                    .containsEntry("queryIntent", "ERROR_CODE")
                    .containsEntry("routeRank", 2);
                assertThat(route.get("sourceEvidence"))
                    .isEqualTo(Map.of(
                        "graphRelationPath", List.of("ERROR_OBSERVED_ON_API", "ENTITY_RELATED_TO_MEMORY"),
                        "graphRelationConfidence", 0.83d,
                        "graphSourceMemoryIds", List.of("memory-seed-05", "memory-graph-05"),
                        "graphEvidenceSummaries", List.of("PAY_401 was observed on payment auth.")
                    ));
            });
        assertThat(bundle.budget().knowledgeTokens()).isEqualTo(9);
        assertThat(bundle.budget().longTermMemoryTokens()).isEqualTo(11);
    }

    @Test
    void budgetPruningDropsCitationsForPrunedRouteCandidatesAndKeepsDiagnostics() {
        var keptKnowledge = knowledgeHit(
            "chunk-kept-05",
            5,
            0.97d,
            Map.of("fusedScore", 0.97d, "fusionExplanation", Map.of("routeCount", 2)),
            List.of(new KnowledgeRouteEvidence("metadata-exact", "qv-kept-knowledge", "ERROR_CODE", 1, 0.97d, "metadata exact match"))
        );
        var prunedKnowledge = knowledgeHit(
            "chunk-pruned-05",
            20,
            0.70d,
            Map.of("fusedScore", 0.70d, "fusionExplanation", Map.of("routeCount", 1)),
            List.of(new KnowledgeRouteEvidence("lexical-tag", "qv-pruned-knowledge", "GENERAL", 4, 0.70d, "lexical fallback match"))
        );
        var keptMemory = memoryHit(
            "memory-kept-05",
            7,
            0.95d,
            Map.of("fusedScore", 0.95d, "fusionExplanation", Map.of("routeCount", 2)),
            List.of(new RetrievalRouteEvidence("semantic_memory", "qv-kept-memory", "FAILURE_ANALYSIS", 1, 0.95d, "semantic match", Map.of()))
        );
        var prunedMemory = memoryHit(
            "memory-pruned-05",
            20,
            0.50d,
            Map.of("fusedScore", 0.50d, "fusionExplanation", Map.of("routeCount", 1)),
            List.of(new RetrievalRouteEvidence("graph_memory", "qv-pruned-memory", "ERROR_CODE", 5, 0.50d, "low confidence graph fallback", Map.of()))
        );

        when(knowledgeRetrieval.retrieve(any(KnowledgeQuery.class))).thenReturn(knowledgeResult(
            List.of(keptKnowledge, prunedKnowledge),
            0.42d,
            true,
            List.of("knowledge-low-confidence: below coverage gate")
        ));
        when(longTermMemoryRetrieval.retrieve(any(LongTermMemoryQuery.class))).thenReturn(new LongTermMemoryRetrievalResult(
            List.of(keptMemory, prunedMemory),
            2,
            27,
            List.of(new RetrievalRouteDiagnostic("graph_memory", "qv-pruned-memory", 6, 0.65d, 0, "empty-route-result"))
        ));

        var bundle = builder().build(query(16));

        assertThat(bundle.knowledgeContext().citedChunks())
            .extracting(KnowledgeContextEntry::chunkId)
            .containsExactly("chunk-kept-05");
        assertThat(bundle.longTermMemoryContext().hits())
            .extracting(LongTermMemoryRetrievalHit::memoryId)
            .containsExactly("memory-kept-05");
        assertThat(bundle.citations())
            .extracting(ContextCitation::sourceId)
            .contains("chunk-kept-05", "memory-kept-05")
            .doesNotContain("chunk-pruned-05", "memory-pruned-05");
        assertThat(bundle.budget().knowledgeTokens()).isEqualTo(5);
        assertThat(bundle.budget().longTermMemoryTokens()).isEqualTo(7);
        assertThat(bundle.budget().totalEstimatedTokens()).isLessThanOrEqualTo(bundle.budget().requestedTokenBudget());
        assertThat(bundle.budget().originalEstimatedTokens()).isGreaterThan(bundle.budget().requestedTokenBudget());
        assertThat(bundle.budget().pruned()).isTrue();

        var diagnostics = retrievalDiagnostics(bundle);
        assertThat(diagnostics.get("knowledge"))
            .isEqualTo(List.of("knowledge-low-confidence: below coverage gate"));
        assertThat(diagnostics.get("longTermMemory"))
            .asList()
            .anySatisfy(diagnostic -> assertThat(diagnostic)
                .isEqualTo(Map.of(
                    "routeName", "graph_memory",
                    "queryVariantId", "qv-pruned-memory",
                    "routeLimit", 6,
                    "confidenceGate", 0.65d,
                    "candidateCount", 0,
                    "diagnostic", "empty-route-result"
                )));
    }

    @Test
    void citationsKeepBaselineShapeWhenRouteEvidenceIsAbsent() {
        when(knowledgeRetrieval.retrieve(any(KnowledgeQuery.class)))
            .thenReturn(knowledgeResult(List.of(knowledgeHit("chunk-baseline-05", 6, 0.82d, Map.of(), List.of())), 0.8d, false, List.of()));
        when(longTermMemoryRetrieval.retrieve(any(LongTermMemoryQuery.class)))
            .thenReturn(new LongTermMemoryRetrievalResult(
                List.of(memoryHit("memory-baseline-05", 6, 0.77d, Map.of(), List.of())),
                1,
                6
            ));

        var bundle = builder().build(query(60));

        assertThat(citationEvidence(bundle, "chunk-baseline-05"))
            .doesNotContainKeys(
                "routeEvidence",
                "queryVariantIds",
                "queryVariantIntents",
                "queryVariantIntentById",
                "routeNames",
                "routeMatchReasons",
                "routeRanks",
                "fusedScore",
                "fusionExplanation"
            );
        assertThat(citationEvidence(bundle, "memory-baseline-05"))
            .doesNotContainKeys(
                "routeEvidence",
                "queryVariantIds",
                "queryVariantIntents",
                "queryVariantIntentById",
                "routeNames",
                "routeMatchReasons",
                "routeRanks",
                "fusedScore",
                "fusionExplanation"
            );
        assertThat(bundle.constraints()).doesNotContainKey("retrievalDiagnostics");
        assertThat(bundle.budget().knowledgeTokens()).isEqualTo(6);
        assertThat(bundle.budget().longTermMemoryTokens()).isEqualTo(6);
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

    private UnifiedContextQuery query(int tokenBudget) {
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
            tokenBudget
        );
    }

    private KnowledgeRetrievalResult knowledgeResult(
        List<KnowledgeRetrievalHit> hits,
        double coverage,
        boolean lowConfidence,
        List<String> diagnostics
    ) {
        var entries = hits.stream()
            .map(this::knowledgeEntry)
            .toList();
        var context = new KnowledgeContext(
            List.of(),
            entries,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            entries,
            lowConfidence,
            false
        );
        return new KnowledgeRetrievalResult(
            "PAY_401 payment auth tenant bootstrap",
            hits,
            context,
            coverage,
            hits.size(),
            hits.stream().mapToInt(KnowledgeRetrievalHit::tokenCount).sum(),
            lowConfidence,
            diagnostics
        );
    }

    private KnowledgeContextEntry knowledgeEntry(KnowledgeRetrievalHit hit) {
        return new KnowledgeContextEntry(
            hit.chunkId(),
            hit.documentId(),
            hit.documentRevisionId(),
            hit.chunkTitle(),
            hit.score(),
            "api_note",
            hit.sourceRef(),
            hit.metadata(),
            hit.matchReasons(),
            hit.lowConfidence()
        );
    }

    private KnowledgeRetrievalHit knowledgeHit(
        String chunkId,
        int tokens,
        double score,
        Map<String, Object> metadata,
        List<KnowledgeRouteEvidence> routeEvidence
    ) {
        return new KnowledgeRetrievalHit(
            chunkId,
            "doc-" + chunkId,
            "rev-" + chunkId,
            "PAY_401 payment auth note " + chunkId,
            "PAY_401 payment auth requires tenant bootstrap for " + chunkId,
            "wiki/payment-auth-" + chunkId + ".md",
            DocumentType.API_NOTE,
            DocumentAuthority.HIGH,
            "billing",
            "payment",
            "order",
            List.of("payment", "auth"),
            List.of("failure_analysis"),
            metadata,
            tokens,
            score,
            Map.of("routeAgreement", 0.2d, "rankContribution", 0.1d),
            List.of("route-fusion", "route-agreement"),
            false,
            routeEvidence
        );
    }

    private LongTermMemoryRetrievalHit memoryHit(
        String memoryId,
        int tokens,
        double score,
        Map<String, Object> metadata,
        List<RetrievalRouteEvidence> routeEvidence
    ) {
        return new LongTermMemoryRetrievalHit(
            memoryId,
            MemoryScopeType.FAILURE_PATTERN,
            "PAY_401 memory " + memoryId,
            "PAY_401 memory says tenant bootstrap is needed before payment auth.",
            "PAY_401 memory says tenant bootstrap is needed before payment auth for " + memoryId,
            List.of("payment", "auth"),
            MemorySourceType.MEMORY_REFINERY,
            "src:" + memoryId,
            0.88f,
            0.81f,
            0.75f,
            3,
            null,
            metadata,
            tokens,
            score,
            Map.of("routeAgreement", 0.2d, "rankContribution", 0.1d),
            List.of("route-fusion", "route-agreement"),
            false,
            routeEvidence
        );
    }

    private ApiSpec tinyApiSpec() {
        var apiSpec = new ApiSpec();
        apiSpec.setApiSpecId("api-issue-05");
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
        apiSpec.setSourceRef("issue-05-openapi.yaml");
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
    private List<Map<String, Object>> routeEvidence(Map<String, Object> evidence) {
        return (List<Map<String, Object>>) evidence.get("routeEvidence");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> retrievalDiagnostics(ContextBundle bundle) {
        return (Map<String, Object>) bundle.constraints().get("retrievalDiagnostics");
    }
}
