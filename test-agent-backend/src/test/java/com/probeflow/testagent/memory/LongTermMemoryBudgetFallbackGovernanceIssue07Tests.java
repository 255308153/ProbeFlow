package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.probeflow.testagent.knowledge.EmbeddingException;
import com.probeflow.testagent.knowledge.EmbeddingFailureCode;
import com.probeflow.testagent.knowledge.EmbeddingProfile;
import com.probeflow.testagent.knowledge.EmbeddingProfileMetadata;
import com.probeflow.testagent.knowledge.EmbeddingService;
import com.probeflow.testagent.memorygraph.MemoryGraphQueryResult;
import com.probeflow.testagent.memorygraph.MemoryGraphQueryService;
import com.probeflow.testagent.memorygraph.MemoryGraphRelatedMemory;
import com.probeflow.testagent.retrieval.QueryFilters;
import com.probeflow.testagent.retrieval.QueryIntent;
import com.probeflow.testagent.retrieval.QueryTargetCorpus;
import com.probeflow.testagent.retrieval.QueryVariant;
import com.probeflow.testagent.retrieval.RetrievalRouteDiagnostic;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryBudgetFallbackGovernanceIssue07Tests {

    @Mock
    private LongTermMemoryRepository longTermMemories;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private MemoryGraphQueryService graphQueryService;

    @Test
    void routeFailureKeepsOtherMemoryRoutesAndSanitizesFallbackDiagnostic() {
        var profile = EmbeddingProfile.fake(4);
        var memory = memory("mem-issue07-safe", 0.90f, 12, metadata(
            "factType", "failure_pattern",
            "systemName", "billing",
            "module", "payment",
            "apiPath", "/api/payments/charge",
            "httpMethod", "POST",
            "errorCode", "GW_TIMEOUT"
        ));
        when(embeddingService.embedQuery(any())).thenThrow(new EmbeddingException(
            EmbeddingFailureCode.REMOTE_ERROR,
            profile.profileId(),
            "sensitive-marker-alpha sensitive-marker-beta sensitive-marker-gamma sensitive-marker-delta"
        ));
        when(longTermMemories.findAllByStatusOrderByCreatedAtAscMemoryIdAsc(MemoryStatus.ACTIVE))
            .thenReturn(List.of(memory));
        when(longTermMemories.findById("mem-issue07-safe")).thenReturn(Optional.of(memory));
        when(longTermMemories.save(memory)).thenReturn(memory);

        var result = new LongTermMemoryRetrievalService(longTermMemories, embeddingService)
            .retrieveWithQueryVariants(baseQuery(4, 200), List.of(memoryVariant()));

        assertThat(result.hits())
            .extracting(LongTermMemoryRetrievalHit::memoryId)
            .containsExactly("mem-issue07-safe");
        assertThat(result.routeDiagnostics())
            .anySatisfy(diagnostic -> {
                assertThat(diagnostic.routeName()).isEqualTo("semantic_memory");
                assertThat(diagnostic.diagnostic()).isEqualTo("route-failed:fallback");
            });
        assertThat(joinDiagnostics(result.routeDiagnostics()))
            .doesNotContain("sensitive-marker-alpha")
            .doesNotContain("sensitive-marker-beta")
            .doesNotContain("sensitive-marker-gamma")
            .doesNotContain("sensitive-marker-delta");
        assertThat(result.hits().getFirst().routeEvidence())
            .extracting(evidence -> evidence.routeName())
            .contains("metadata_memory", "exact_entity")
            .doesNotContain("semantic_memory");
    }

    @Test
    void allRoutesEmptyReturnLowCoverageDiagnostic() {
        var profile = EmbeddingProfile.fake(4);
        when(embeddingService.profile()).thenReturn(profile);
        when(embeddingService.dimensions()).thenReturn(4);
        when(embeddingService.embedQuery(any())).thenReturn(new float[] {0.1f, 0.2f, 0.3f, 0.4f});
        when(longTermMemories.findPgvectorCandidates(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(List.of());
        when(longTermMemories.findAllByStatusOrderByCreatedAtAscMemoryIdAsc(MemoryStatus.ACTIVE))
            .thenReturn(List.of());

        var result = new LongTermMemoryRetrievalService(longTermMemories, embeddingService)
            .retrieveWithQueryVariants(baseQuery(4, 200), List.of(memoryVariant()));

        assertThat(result.hits()).isEmpty();
        assertThat(result.totalCandidates()).isZero();
        assertThat(result.totalTokens()).isZero();
        assertThat(result.routeDiagnostics())
            .anySatisfy(diagnostic -> {
                assertThat(diagnostic.routeName()).isEqualTo("route_fusion");
                assertThat(diagnostic.candidateCount()).isZero();
                assertThat(diagnostic.diagnostic()).isEqualTo("low-coverage:all-routes-empty");
            });
    }

    @Test
    void lowConfidenceGraphRelationDoesNotEnterDefaultContext() {
        when(graphQueryService.queryRelated(any(), anyInt(), anyInt())).thenReturn(new MemoryGraphQueryResult(
            null,
            List.of(),
            List.of(new MemoryGraphRelatedMemory(
                "mem-issue07-low-graph",
                "Low confidence graph relation",
                "src:low-graph",
                "graph",
                "weak relation",
                List.of("RELATED_TO"),
                0.20d,
                List.of("mem-source"),
                List.of("src:source"),
                List.of("fact:source"),
                List.of("weak evidence")
            ))
        ));

        var result = new LongTermMemoryRetrievalService(longTermMemories, embeddingService, graphQueryService)
            .retrieveWithQueryVariants(baseQuery(4, 200), List.of(graphVariant()));

        assertThat(result.hits()).isEmpty();
        assertThat(result.routeDiagnostics())
            .anySatisfy(diagnostic -> {
                assertThat(diagnostic.routeName()).isEqualTo("graph_memory");
                assertThat(diagnostic.queryVariantId()).isEqualTo("qv-issue07-graph");
                assertThat(diagnostic.diagnostic()).isEqualTo("empty-route-result");
            })
            .anySatisfy(diagnostic -> {
                assertThat(diagnostic.routeName()).isEqualTo("route_fusion");
                assertThat(diagnostic.diagnostic()).isEqualTo("low-coverage:all-routes-empty");
            });
    }

    @Test
    void budgetPruningKeepsCandidateCountDiagnosticsAndEvidenceConsistent() {
        var oversized = memory("mem-issue07-oversized", 0.91f, 120, metadata(
            "factType", "failure_pattern",
            "systemName", "billing",
            "module", "payment",
            "apiPath", "/api/payments/charge",
            "httpMethod", "POST",
            "errorCode", "GW_TIMEOUT"
        ));
        when(longTermMemories.findAllByStatusOrderByCreatedAtAscMemoryIdAsc(MemoryStatus.ACTIVE))
            .thenReturn(List.of(oversized));

        var result = new LongTermMemoryRetrievalService(longTermMemories, embeddingService)
            .retrieveWithQueryVariants(baseQuery(4, 20), List.of(metadataOnlyVariant()));

        assertThat(result.hits()).isEmpty();
        assertThat(result.totalCandidates()).isEqualTo(1);
        assertThat(result.totalTokens()).isZero();
        assertThat(result.routeDiagnostics())
            .anySatisfy(diagnostic -> {
                assertThat(diagnostic.routeName()).isEqualTo("route_fusion");
                assertThat(diagnostic.candidateCount()).isEqualTo(1);
                assertThat(diagnostic.diagnostic()).isEqualTo("low-coverage:budget-pruned-all-candidates");
            });
    }

    @Test
    void fusionCandidateLimitCapsExplodingMemoryRouteCandidatePool() {
        var memories = new ArrayList<LongTermMemory>();
        var variants = new ArrayList<QueryVariant>();
        for (var group = 0; group < 5; group++) {
            var errorCode = "GW_LIMIT_" + group;
            variants.add(explosionVariant(group, errorCode));
            for (var index = 0; index < 6; index++) {
                memories.add(memory("mem-issue07-limit-" + group + "-" + index, 0.92f, 8, metadata(
                    "factType", "failure_pattern",
                    "errorCode", errorCode
                )));
            }
        }
        when(longTermMemories.findAllByStatusOrderByCreatedAtAscMemoryIdAsc(MemoryStatus.ACTIVE))
            .thenReturn(memories);
        when(longTermMemories.findById(any())).thenAnswer(invocation -> {
            var memoryId = invocation.getArgument(0, String.class);
            return memories.stream()
                .filter(memory -> memory.getMemoryId().equals(memoryId))
                .findFirst();
        });
        when(longTermMemories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = new LongTermMemoryRetrievalService(longTermMemories, embeddingService)
            .retrieveWithQueryVariants(explosionQuery(), variants);

        assertThat(result.totalCandidates()).isEqualTo(24);
        assertThat(result.hits()).hasSize(4);
        assertThat(result.routeDiagnostics())
            .anySatisfy(diagnostic -> {
                assertThat(diagnostic.routeName()).isEqualTo("route_fusion");
                assertThat(diagnostic.candidateCount()).isEqualTo(30);
                assertThat(diagnostic.diagnostic()).isEqualTo("candidate-limit-applied:24");
            });
    }

    private static LongTermMemoryQuery baseQuery(int limit, int tokenBudget) {
        return new LongTermMemoryQuery(
            "failure_analysis",
            "GW_TIMEOUT payment charge retry",
            "billing",
            "payment",
            "/api/payments/charge",
            "GW_TIMEOUT",
            List.of("payment"),
            List.of(MemoryScopeType.FAILURE_PATTERN),
            limit,
            tokenBudget
        );
    }

    private static LongTermMemoryQuery explosionQuery() {
        return new LongTermMemoryQuery(
            "failure_analysis",
            "candidate pool limit",
            null,
            null,
            null,
            null,
            List.of(),
            List.of(MemoryScopeType.FAILURE_PATTERN),
            4,
            500
        );
    }

    private static QueryVariant memoryVariant() {
        return new QueryVariant(
            "qv-issue07-memory",
            "GW_TIMEOUT payment charge retry",
            QueryIntent.FAILURE_REASON,
            QueryTargetCorpus.MEMORY,
            "failure_analysis",
            filters(List.of("payment")),
            95,
            "Issue 07 should keep metadata and exact memory routes when semantic fallback triggers."
        );
    }

    private static QueryVariant metadataOnlyVariant() {
        return new QueryVariant(
            "qv-issue07-metadata-budget",
            "",
            QueryIntent.FAILURE_REASON,
            QueryTargetCorpus.MEMORY,
            "failure_analysis",
            filters(List.of("payment")),
            90,
            "Issue 07 should diagnose budget pruning without losing route evidence consistency."
        );
    }

    private static QueryVariant explosionVariant(int index, String errorCode) {
        return new QueryVariant(
            "qv-issue07-limit-" + index,
            errorCode,
            QueryIntent.FAILURE_REASON,
            QueryTargetCorpus.MEMORY,
            "failure_analysis",
            new QueryFilters(
                null,
                null,
                null,
                null,
                null,
                errorCode,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(MemoryFactType.FAILURE_PATTERN)
            ),
            80,
            "Issue 07 should cap exploding route candidate pools."
        );
    }

    private static QueryVariant graphVariant() {
        return new QueryVariant(
            "qv-issue07-graph",
            "GW_TIMEOUT graph relation",
            QueryIntent.FAILURE_REASON,
            QueryTargetCorpus.GRAPH,
            "failure_analysis",
            filters(List.of("payment")),
            80,
            "Low confidence graph relation should stay out of default context."
        );
    }

    private static QueryFilters filters(List<String> tags) {
        return new QueryFilters(
            "billing",
            "payment",
            "/api/payments/charge",
            "POST",
            "Payment",
            "GW_TIMEOUT",
            null,
            null,
            null,
            null,
            null,
            tags,
            List.of(),
            List.of(MemoryFactType.FAILURE_PATTERN)
        );
    }

    private static LongTermMemory memory(
        String memoryId,
        float confidence,
        int tokenCount,
        Map<String, Object> metadata
    ) {
        var profile = EmbeddingProfile.fake(4);
        var memory = new LongTermMemory();
        var content = "Payment charge GW_TIMEOUT requires retry fixture. ".repeat(Math.max(1, tokenCount / 2));
        memory.setMemoryId(memoryId);
        memory.setMemoryType(MemoryType.LONG_TERM);
        memory.setScopeType(MemoryScopeType.FAILURE_PATTERN);
        memory.setSummary("GW_TIMEOUT retry fixture " + memoryId);
        memory.setContent(content);
        memory.setFullContent(content + "Full evidence.");
        memory.setTags(List.of("payment"));
        memory.setSourceType(MemorySourceType.MEMORY_REFINERY);
        memory.setSourceRef("src:" + memoryId);
        memory.setConfidence(confidence);
        memory.setImportance(0.82f);
        memory.setSuccessContribution(0.75f);
        memory.setHitCount(0);
        memory.setLastUsedAt(Instant.EPOCH);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setMetadata(EmbeddingProfileMetadata.withProfile(new LinkedHashMap<>(metadata), profile));
        memory.setEmbedding(new float[4]);
        return memory;
    }

    private static Map<String, Object> metadata(Object... values) {
        var metadata = new LinkedHashMap<String, Object>();
        for (var index = 0; index + 1 < values.length; index += 2) {
            metadata.put(String.valueOf(values[index]), values[index + 1]);
        }
        return metadata;
    }

    private static String joinDiagnostics(List<RetrievalRouteDiagnostic> diagnostics) {
        return diagnostics.stream()
            .map(RetrievalRouteDiagnostic::diagnostic)
            .reduce("", (left, right) -> left + " " + right);
    }
}
