package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.knowledge.EmbeddingService;
import com.probeflow.testagent.memorygraph.MemoryGraphEdgeRepository;
import com.probeflow.testagent.memorygraph.MemoryGraphNodeRepository;
import com.probeflow.testagent.memorygraph.MemoryGraphProjectionService;
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
class LongTermMemoryRouteRetrievalIssue03Tests {

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private LongTermMemoryRetrievalService retrievalService;

    @Autowired
    private MemoryGraphProjectionService projectionService;

    @Autowired
    private MemoryGraphNodeRepository nodes;

    @Autowired
    private MemoryGraphEdgeRepository edges;

    @Autowired
    private EmbeddingService embeddingService;

    @BeforeEach
    void clean() {
        edges.deleteAll();
        nodes.deleteAll();
        longTermMemories.deleteAll();
    }

    @AfterEach
    void cleanAfter() {
        clean();
    }

    @Test
    void retrievesCandidatesAcrossSemanticMetadataGraphAndExactRoutesFromQueryVariants() {
        longTermMemories.save(memory(
            "mem-issue03-semantic",
            MemoryScopeType.FAILURE_PATTERN,
            "GW_TIMEOUT retry seed for charge API",
            "Gateway timeout on charge requires retry fixture and tenant bootstrap.",
            List.of("payment", "gateway", "semantic-seed"),
            0.90f,
            metadata(
                "factType", "failure_pattern",
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "httpMethod", "POST",
                "errorCode", "GW_TIMEOUT",
                "businessEntity", "Payment",
                "factFingerprint", "fact:issue03-semantic",
                "evidenceSummary", "Semantic seed observed GW_TIMEOUT on the charge API."
            ),
            MemoryStatus.ACTIVE
        ));
        longTermMemories.save(memory(
            "mem-issue03-graph",
            MemoryScopeType.TESTING_PATTERN,
            "Use payable order fixture before charge",
            "Prepare a payable order before charge because GW_TIMEOUT often follows missing order state.",
            List.of("payment", "graph-related"),
            0.88f,
            metadata(
                "factType", "testing_pattern",
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "httpMethod", "POST",
                "errorCode", "GW_TIMEOUT",
                "businessEntity", "Payment",
                "factFingerprint", "fact:issue03-graph",
                "evidenceSummary", "Graph related memory links GW_TIMEOUT to a payable order fixture."
            ),
            MemoryStatus.ACTIVE
        ));
        longTermMemories.save(memory(
            "mem-issue03-suite",
            MemoryScopeType.FAILURE_PATTERN,
            "Capture paymentToken for downstream charge",
            "Suite recovery should extract paymentToken from the authorize step before charge.",
            List.of("payment", "suite"),
            0.86f,
            metadata(
                "factType", "variable_extraction_fact",
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "httpMethod", "POST",
                "businessEntity", "Payment",
                "suiteId", "checkout-suite",
                "variableKey", "paymentToken",
                "factFingerprint", "fact:issue03-suite",
                "evidenceSummary", "Suite evidence shows paymentToken is required before charge."
            ),
            MemoryStatus.ACTIVE
        ));
        longTermMemories.save(memory(
            "mem-issue03-policy",
            MemoryScopeType.FAILURE_PATTERN,
            "Planner requires approval before deleting payment fixture",
            "The cleanup tool should wait for human approval when deleting payment fixtures.",
            List.of("payment", "policy"),
            0.84f,
            metadata(
                "factType", "policy_learning",
                "systemName", "billing",
                "module", "payment",
                "policyReason", "requires-human-approval",
                "toolName", "delete-payment-fixture",
                "factFingerprint", "fact:issue03-policy",
                "evidenceSummary", "Policy learning came from a human approval decision."
            ),
            MemoryStatus.ACTIVE
        ));
        longTermMemories.save(memory(
            "mem-issue03-inactive",
            MemoryScopeType.FAILURE_PATTERN,
            "Inactive exact entity should stay hidden",
            "Inactive memories must not enter the default read result.",
            List.of("payment", "policy"),
            0.92f,
            metadata("factType", "policy_learning", "policyReason", "requires-human-approval", "toolName", "delete-payment-fixture"),
            MemoryStatus.INACTIVE
        ));
        longTermMemories.save(memory(
            "mem-issue03-archived",
            MemoryScopeType.FAILURE_PATTERN,
            "Archived exact entity should stay hidden",
            "Archived memories must not enter the default read result.",
            List.of("payment", "policy"),
            0.92f,
            metadata("factType", "policy_learning", "policyReason", "requires-human-approval", "toolName", "delete-payment-fixture"),
            MemoryStatus.ARCHIVED
        ));
        longTermMemories.save(memory(
            "mem-issue03-low-confidence",
            MemoryScopeType.FAILURE_PATTERN,
            "Low confidence exact entity should stay hidden",
            "Low confidence memories are not promoted by exact entity route.",
            List.of("payment", "policy"),
            0.31f,
            metadata("factType", "policy_learning", "policyReason", "requires-human-approval", "toolName", "delete-payment-fixture"),
            MemoryStatus.ACTIVE
        ));
        projectionService.rebuild();

        var result = retrievalService.retrieveWithQueryVariants(baseQuery(), queryVariants());

        assertThat(result.hits())
            .extracting(LongTermMemoryRetrievalHit::memoryId)
            .contains("mem-issue03-semantic", "mem-issue03-graph", "mem-issue03-suite", "mem-issue03-policy")
            .doesNotContain("mem-issue03-inactive", "mem-issue03-archived", "mem-issue03-low-confidence")
            .doesNotHaveDuplicates();
        assertThat(allRouteNames(result.hits()))
            .contains("semantic_memory", "metadata_memory", "graph_memory", "exact_entity");
        var policyHit = result.hits().stream()
            .filter(hit -> hit.memoryId().equals("mem-issue03-policy"))
            .findFirst()
            .orElseThrow();
        assertThat(policyHit.routeEvidence())
            .extracting(RetrievalRouteEvidence::routeName)
            .contains("exact_entity");
        assertThat(result.hits())
            .flatExtracting(LongTermMemoryRetrievalHit::routeEvidence)
            .allSatisfy(evidence -> {
                assertThat(evidence.routeName()).isNotBlank();
                assertThat(evidence.queryVariantId()).startsWith("qv-issue03-");
                assertThat(evidence.routeRank()).isPositive();
                assertThat(evidence.routeScore()).isPositive();
                assertThat(evidence.matchReason()).isNotBlank();
                assertThat(evidence.sourceEvidence()).isNotEmpty();
            });
        assertThat(result.routeDiagnostics())
            .extracting(diagnostic -> diagnostic.routeName())
            .contains("semantic_memory", "metadata_memory", "graph_memory", "exact_entity");
        assertThat(result.routeDiagnostics())
            .allSatisfy(diagnostic -> {
                assertThat(diagnostic.routeLimit()).isPositive();
                assertThat(diagnostic.confidenceGate()).isGreaterThanOrEqualTo(0.0d);
                assertThat(diagnostic.diagnostic()).isNotBlank();
            });

        var graphHit = result.hits().stream()
            .filter(hit -> hit.routeEvidence().stream().anyMatch(evidence -> evidence.routeName().equals("graph_memory")))
            .findFirst()
            .orElseThrow();
        assertThat(graphHit.metadata()).containsKeys(
            "graphRelationPath",
            "graphRelationConfidence",
            "graphEvidenceSummaries",
            "routeEvidence"
        );
        assertThat(graphHit.routeEvidence().stream().map(RetrievalRouteEvidence::routeName))
            .contains("graph_memory");
    }

    @Test
    void graphRouteEmptyResultKeepsOtherRouteCandidatesAndRecordsDiagnostic() {
        longTermMemories.save(memory(
            "mem-issue03-semantic-only",
            MemoryScopeType.FAILURE_PATTERN,
            "GW_TIMEOUT retry seed for charge API",
            "Gateway timeout on charge requires retry fixture.",
            List.of("payment", "gateway", "semantic-seed"),
            0.90f,
            metadata(
                "factType", "failure_pattern",
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "httpMethod", "POST",
                "errorCode", "GW_TIMEOUT"
            ),
            MemoryStatus.ACTIVE
        ));
        projectionService.rebuild();

        var result = retrievalService.retrieveWithQueryVariants(baseQuery(), List.of(
            queryVariants().getFirst(),
            new QueryVariant(
                "qv-issue03-graph-miss",
                "missing graph relation",
                QueryIntent.FAILURE_REASON,
                QueryTargetCorpus.GRAPH,
                "failure_analysis",
                new QueryFilters(
                    "unknown-system",
                    "unknown-module",
                    "/api/unknown/missing",
                    "POST",
                    "MissingEntity",
                    "NO_SUCH_ERROR",
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of("missing"),
                    List.of(),
                    List.of(MemoryFactType.FAILURE_PATTERN)
                ),
                80,
                "Missed graph seed should not block other route results."
            )
        ));

        assertThat(result.hits())
            .extracting(LongTermMemoryRetrievalHit::memoryId)
            .contains("mem-issue03-semantic-only");
        assertThat(result.routeDiagnostics())
            .anySatisfy(diagnostic -> {
                assertThat(diagnostic.routeName()).isEqualTo("graph_memory");
                assertThat(diagnostic.queryVariantId()).isEqualTo("qv-issue03-graph-miss");
                assertThat(diagnostic.candidateCount()).isZero();
                assertThat(diagnostic.diagnostic()).isEqualTo("empty-route-result");
            });
    }

    private LongTermMemoryQuery baseQuery() {
        return new LongTermMemoryQuery(
            "failure_analysis",
            "GW_TIMEOUT payment charge retry policy and suite memory",
            "billing",
            "payment",
            "/api/payments/charge",
            "GW_TIMEOUT",
            List.of("payment"),
            List.of(),
            8,
            1000
        );
    }

    private List<QueryVariant> queryVariants() {
        return List.of(
            new QueryVariant(
                "qv-issue03-semantic",
                "GW_TIMEOUT gateway retry semantic seed",
                QueryIntent.RAW_TASK,
                QueryTargetCorpus.ALL,
                "failure_analysis",
                new QueryFilters(
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
                    List.of("payment", "semantic-seed"),
                    List.of(),
                    List.of(MemoryFactType.FAILURE_PATTERN)
                ),
                100,
                "Semantic seed keeps the existing vector-backed memory route available."
            ),
            new QueryVariant(
                "qv-issue03-suite-metadata",
                "paymentToken suite variable dependency",
                QueryIntent.SUITE_VARIABLE,
                QueryTargetCorpus.MEMORY,
                "suite_recovery",
                new QueryFilters(
                    "billing",
                    "payment",
                    "/api/payments/charge",
                    "POST",
                    "Payment",
                    null,
                    null,
                    "checkout-suite",
                    "paymentToken",
                    null,
                    null,
                    List.of("payment", "suite"),
                    List.of(),
                    List.of(MemoryFactType.VARIABLE_EXTRACTION_FACT)
                ),
                90,
                "Suite work should use variable dependency memory metadata."
            ),
            new QueryVariant(
                "qv-issue03-policy-exact",
                "delete payment fixture approval policy",
                QueryIntent.POLICY_LEARNING,
                QueryTargetCorpus.MEMORY,
                "planner_policy",
                new QueryFilters(
                    "billing",
                    "payment",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "requires-human-approval",
                    "delete-payment-fixture",
                    List.of("payment", "policy"),
                    List.of(),
                    List.of(MemoryFactType.POLICY_LEARNING)
                ),
                85,
                "Policy learning should use high precision tool and reason entities."
            ),
            new QueryVariant(
                "qv-issue03-graph",
                "GW_TIMEOUT graph related payment memory",
                QueryIntent.FAILURE_REASON,
                QueryTargetCorpus.GRAPH,
                "failure_analysis",
                new QueryFilters(
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
                    List.of("payment"),
                    List.of(),
                    List.of(MemoryFactType.FAILURE_PATTERN)
                ),
                80,
                "Graph route should expand from precise error and API entities."
            )
        );
    }

    private LongTermMemory memory(
        String memoryId,
        MemoryScopeType scopeType,
        String summary,
        String content,
        List<String> tags,
        float confidence,
        Map<String, Object> metadata,
        MemoryStatus status
    ) {
        var memory = new LongTermMemory();
        memory.setMemoryId(memoryId);
        memory.setMemoryType(MemoryType.LONG_TERM);
        memory.setScopeType(scopeType);
        memory.setSummary(summary);
        memory.setContent(content);
        memory.setFullContent(content + " Full evidence.");
        memory.setTags(tags);
        memory.setSourceType(MemorySourceType.MEMORY_REFINERY);
        memory.setSourceRef("src:" + memoryId);
        memory.setConfidence(confidence);
        memory.setImportance(0.80f);
        memory.setSuccessContribution(0.70f);
        memory.setHitCount(0);
        memory.setStatus(status);
        memory.setMetadata(new LinkedHashMap<>(metadata));
        memory.setEmbedding(embeddingService.embedDocument(summary + "\n" + content));
        return memory;
    }

    private Map<String, Object> metadata(Object... values) {
        var metadata = new LinkedHashMap<String, Object>();
        for (var index = 0; index + 1 < values.length; index += 2) {
            metadata.put(String.valueOf(values[index]), values[index + 1]);
        }
        return metadata;
    }

    private List<String> allRouteNames(List<LongTermMemoryRetrievalHit> hits) {
        return hits.stream()
            .flatMap(hit -> hit.routeEvidence().stream())
            .map(RetrievalRouteEvidence::routeName)
            .distinct()
            .toList();
    }
}
