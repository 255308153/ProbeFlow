package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.knowledge.EmbeddingService;
import com.probeflow.testagent.retrieval.QueryFilters;
import com.probeflow.testagent.retrieval.QueryIntent;
import com.probeflow.testagent.retrieval.QueryTargetCorpus;
import com.probeflow.testagent.retrieval.QueryVariant;
import com.probeflow.testagent.retrieval.RetrievalRouteEvidence;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LongTermMemoryRouteFusionIssue04Tests {

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private LongTermMemoryRetrievalService retrievalService;

    @Autowired
    private EmbeddingService embeddingService;

    @BeforeEach
    void clean() {
        longTermMemories.deleteAll();
    }

    @AfterEach
    void cleanAfter() {
        clean();
    }

    @Test
    void fusesDuplicateMemoryRoutesWithoutBypassingStatusConfidenceOrBudget() {
        longTermMemories.save(memory(
            "mem-issue04-fused",
            0.91f,
            MemoryStatus.ACTIVE,
            List.of("payment", "gateway", "issue04"),
            metadata(
                "factType", "failure_pattern",
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "httpMethod", "POST",
                "errorCode", "GW_TIMEOUT",
                "factFingerprint", "fact:issue04-fused",
                "evidenceSummary", "GW_TIMEOUT on charge needs retry fixture."
            )
        ));
        longTermMemories.save(memory(
            "mem-issue04-inactive",
            0.96f,
            MemoryStatus.INACTIVE,
            List.of("payment", "gateway", "issue04"),
            metadata("errorCode", "GW_TIMEOUT", "apiPath", "/api/payments/charge", "factType", "failure_pattern")
        ));
        longTermMemories.save(memory(
            "mem-issue04-low-confidence",
            0.20f,
            MemoryStatus.ACTIVE,
            List.of("payment", "gateway", "issue04"),
            metadata("errorCode", "GW_TIMEOUT", "apiPath", "/api/payments/charge", "factType", "failure_pattern")
        ));

        var result = retrievalService.retrieveWithQueryVariants(baseQuery(), queryVariants());

        assertThat(result.hits())
            .extracting(LongTermMemoryRetrievalHit::memoryId)
            .containsExactly("mem-issue04-fused")
            .doesNotContain("mem-issue04-inactive", "mem-issue04-low-confidence");
        assertThat(result.totalCandidates()).isEqualTo(1);
        assertThat(result.totalTokens()).isLessThanOrEqualTo(80);
        assertThat(result.routeDiagnostics())
            .anySatisfy(diagnostic -> {
                assertThat(diagnostic.routeName()).isEqualTo("route_fusion");
                assertThat(diagnostic.diagnostic()).startsWith("deduplicated-routes:");
            });

        var hit = result.hits().getFirst();
        assertThat(hit.lowConfidence()).isFalse();
        assertThat(hit.routeEvidence())
            .extracting(RetrievalRouteEvidence::routeName)
            .contains("semantic_memory", "metadata_memory", "exact_entity");
        assertThat(hit.routeEvidence())
            .allSatisfy(evidence -> {
                assertThat(evidence.queryVariantId()).startsWith("qv-issue04-");
                assertThat(evidence.routeRank()).isPositive();
                assertThat(evidence.routeScore()).isPositive();
                assertThat(evidence.matchReason()).isNotBlank();
            });
        assertThat(hit.metadata())
            .containsKeys(
                "routeEvidence",
                "retrievalRoutes",
                "queryVariantIds",
                "preFusionRank",
                "preFusionRanks",
                "candidateRank",
                "fusedScore",
                "fusionExplanation"
            );
        assertThat(((Number) hit.metadata().get("fusedScore")).doubleValue()).isEqualTo(hit.score());
        assertThat(hit.componentScores()).containsKeys("routeAgreement", "rankContribution");
        assertThat(hit.matchReasons()).contains("route-fusion", "route-agreement");

        var routeMetadata = routeMetadata(hit);
        assertThat(routeMetadata)
            .hasSize(hit.routeEvidence().size())
            .allSatisfy(route -> assertThat(route)
                .containsKeys(
                    "routeName",
                    "queryVariantId",
                    "routeRank",
                    "routeScore",
                    "matchReason",
                    "routeWeight",
                    "weightedRouteScore",
                    "rankContribution",
                    "sourceEvidence"
                ));

        var explanation = explanation(hit);
        assertThat(explanation.get("formula")).isEqualTo("bestWeightedRouteScore + rankContributionTotal + routeAgreementBonus");
        assertThat(((Number) explanation.get("routeCount")).intValue()).isEqualTo(hit.routeEvidence().size());
        assertThat(((Number) explanation.get("rankContributionTotal")).doubleValue()).isPositive();
        assertThat(((Number) explanation.get("routeAgreementBonus")).doubleValue()).isPositive();
    }

    private LongTermMemoryQuery baseQuery() {
        return new LongTermMemoryQuery(
            "failure_analysis",
            "GW_TIMEOUT payment charge retry fixture issue04",
            "billing",
            "payment",
            "/api/payments/charge",
            "GW_TIMEOUT",
            List.of("payment", "gateway", "issue04"),
            List.of(MemoryScopeType.FAILURE_PATTERN),
            3,
            80
        );
    }

    private List<QueryVariant> queryVariants() {
        return List.of(
            new QueryVariant(
                "qv-issue04-semantic",
                "GW_TIMEOUT payment charge retry fixture issue04",
                QueryIntent.RAW_TASK,
                QueryTargetCorpus.MEMORY,
                "failure_analysis",
                filters(List.of("payment", "gateway", "issue04")),
                100,
                "Semantic memory route should find the failure pattern."
            ),
            new QueryVariant(
                "qv-issue04-metadata",
                "payment charge metadata route",
                QueryIntent.FAILURE_REASON,
                QueryTargetCorpus.MEMORY,
                "failure_analysis",
                filters(List.of("payment", "gateway", "issue04")),
                95,
                "Metadata route should preserve exact API and error evidence."
            ),
            new QueryVariant(
                "qv-issue04-exact",
                "GW_TIMEOUT exact entity",
                QueryIntent.ERROR_CODE,
                QueryTargetCorpus.MEMORY,
                "failure_analysis",
                filters(List.of("payment")),
                90,
                "Exact entity route should contribute a separate pre-fusion rank."
            )
        );
    }

    private QueryFilters filters(List<String> tags) {
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

    private LongTermMemory memory(
        String memoryId,
        float confidence,
        MemoryStatus status,
        List<String> tags,
        Map<String, Object> metadata
    ) {
        var memory = new LongTermMemory();
        memory.setMemoryId(memoryId);
        memory.setMemoryType(MemoryType.LONG_TERM);
        memory.setScopeType(MemoryScopeType.FAILURE_PATTERN);
        memory.setSummary("GW_TIMEOUT retry fixture for payment charge " + memoryId);
        memory.setContent("Payment charge GW_TIMEOUT requires retry fixture and tenant bootstrap before assertions.");
        memory.setFullContent(memory.getContent() + " Full evidence.");
        memory.setTags(tags);
        memory.setSourceType(MemorySourceType.MEMORY_REFINERY);
        memory.setSourceRef("src:" + memoryId);
        memory.setConfidence(confidence);
        memory.setImportance(0.82f);
        memory.setSuccessContribution(0.75f);
        memory.setHitCount(0);
        memory.setStatus(status);
        memory.setMetadata(new LinkedHashMap<>(metadata));
        memory.setEmbedding(embeddingService.embedDocument(memory.getSummary() + "\n" + memory.getContent()));
        return memory;
    }

    private Map<String, Object> metadata(Object... values) {
        var metadata = new LinkedHashMap<String, Object>();
        for (var index = 0; index + 1 < values.length; index += 2) {
            metadata.put(String.valueOf(values[index]), values[index + 1]);
        }
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> routeMetadata(LongTermMemoryRetrievalHit hit) {
        return (List<Map<String, Object>>) hit.metadata().get("routeEvidence");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> explanation(LongTermMemoryRetrievalHit hit) {
        return (Map<String, Object>) hit.metadata().get("fusionExplanation");
    }
}
