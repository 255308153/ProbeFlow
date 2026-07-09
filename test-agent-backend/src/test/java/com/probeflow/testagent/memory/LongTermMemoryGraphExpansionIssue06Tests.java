package com.probeflow.testagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.memorygraph.MemoryGraphEdgeRepository;
import com.probeflow.testagent.memorygraph.MemoryGraphNodeRepository;
import com.probeflow.testagent.memorygraph.MemoryGraphProjectionService;
import com.probeflow.testagent.memorygraph.MemoryGraphRelationType;
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
class LongTermMemoryGraphExpansionIssue06Tests {

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private MemoryGraphProjectionService projectionService;

    @Autowired
    private LongTermMemoryRetrievalService retrievalService;

    @Autowired
    private MemoryGraphNodeRepository nodes;

    @Autowired
    private MemoryGraphEdgeRepository edges;

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
    void expandsSemanticSeedHitsThroughMemoryGraphWithGraphChannelEvidenceAndBudgetLimit() {
        longTermMemories.save(memory(
            "mem-v5-3-06-seed",
            "Payment seed memory for PAY_401 investigation",
            "Seed content mentions PAY_401 and a charge API failure.",
            List.of("seed-payment"),
            0.89f,
            0.82f,
            0.70f,
            Map.of(
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "httpMethod", "POST",
                "errorCode", "PAY_401",
                "factType", "failure_pattern",
                "factFingerprint", "fact:issue06-seed",
                "evidenceSummary", "Seed failure memory contains the graph seed entities."
            )
        ));
        longTermMemories.save(memory(
            "mem-v5-3-06-graph",
            "Use payable order fixture before charge request",
            "Prepare a payable order fixture before executing charge because PAY_401 often means missing auth state.",
            List.of("graph-payment"),
            0.86f,
            0.80f,
            0.78f,
            Map.of(
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "httpMethod", "POST",
                "errorCode", "PAY_401",
                "businessEntity", "payment order",
                "factType", "testing_pattern",
                "factFingerprint", "fact:issue06-related",
                "evidenceSummary", "Related testing memory is connected by PAY_401 on the charge API."
            )
        ));
        projectionService.rebuild();

        var result = retrievalService.retrieve(new LongTermMemoryQuery(
            "failure_analysis",
            "PAY_401 seed-payment charge investigation",
            "billing",
            "payment",
            "/api/payments/charge",
            "PAY_401",
            List.of("seed-payment"),
            List.of(),
            2,
            220
        ));

        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().getFirst().memoryId()).isEqualTo("mem-v5-3-06-seed");
        assertThat(result.hits().getFirst().metadata()).containsEntry("retrievalChannel", "pgvector");

        var graphHit = result.hits().get(1);
        assertThat(graphHit.memoryId()).isEqualTo("mem-v5-3-06-graph");
        assertThat(graphHit.metadata())
            .containsEntry("retrievalChannel", "graph")
            .containsEntry("graphMatchReason", "graph-related-error-code");
        assertThat(graphHit.matchReasons()).containsExactly("graph-related-error-code");
        assertThat(graphHit.metadata().get("graphRelationPath").toString())
            .contains(MemoryGraphRelationType.ERROR_OBSERVED_ON_API.name());
        assertThat((Double) graphHit.metadata().get("graphRelationConfidence"))
            .isGreaterThanOrEqualTo(com.probeflow.testagent.memorygraph.MemoryGraphQueryService.DEFAULT_CONFIDENCE_GATE);
        assertThat(graphHit.metadata().get("graphSourceMemoryIds").toString())
            .contains("mem-v5-3-06-graph");
        assertThat(graphHit.metadata().get("graphFactFingerprints").toString())
            .contains("fact:issue06-related");
        assertThat(graphHit.metadata().get("graphEvidenceSummaries").toString())
            .contains("Related testing memory is connected by PAY_401");
        assertThat(result.totalTokens()).isLessThanOrEqualTo(220);
        assertThat(result.hits())
            .extracting(LongTermMemoryRetrievalHit::memoryId)
            .doesNotHaveDuplicates();

        var constrained = retrievalService.retrieve(new LongTermMemoryQuery(
            "failure_analysis",
            "PAY_401 seed-payment charge investigation",
            "billing",
            "payment",
            "/api/payments/charge",
            "PAY_401",
            List.of("seed-payment"),
            List.of(),
            1,
            220
        ));
        assertThat(constrained.hits())
            .extracting(LongTermMemoryRetrievalHit::memoryId)
            .containsExactly("mem-v5-3-06-seed");
    }

    private LongTermMemory memory(
        String memoryId,
        String summary,
        String content,
        List<String> tags,
        float confidence,
        float importance,
        float success,
        Map<String, Object> metadata
    ) {
        var memory = new LongTermMemory();
        memory.setMemoryId(memoryId);
        memory.setMemoryType(MemoryType.LONG_TERM);
        memory.setScopeType(MemoryScopeType.FAILURE_PATTERN);
        memory.setSummary(summary);
        memory.setContent(content);
        memory.setFullContent(content + " full evidence");
        memory.setTags(tags);
        memory.setSourceType(MemorySourceType.MEMORY_REFINERY);
        memory.setSourceRef("src:" + memoryId);
        memory.setConfidence(confidence);
        memory.setImportance(importance);
        memory.setSuccessContribution(success);
        memory.setHitCount(0);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setMetadata(new LinkedHashMap<>(metadata));
        memory.setEmbedding(new float[1024]);
        return memory;
    }
}
