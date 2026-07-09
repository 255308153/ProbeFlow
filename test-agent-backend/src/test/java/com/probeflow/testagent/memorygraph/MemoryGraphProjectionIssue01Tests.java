package com.probeflow.testagent.memorygraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.probeflow.testagent.memory.LongTermMemory;
import com.probeflow.testagent.memory.LongTermMemoryRepository;
import com.probeflow.testagent.memory.MemoryScopeType;
import com.probeflow.testagent.memory.MemorySourceType;
import com.probeflow.testagent.memory.MemoryStatus;
import com.probeflow.testagent.memory.MemoryType;
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
class MemoryGraphProjectionIssue01Tests {

    @Autowired
    private LongTermMemoryRepository longTermMemories;

    @Autowired
    private MemoryGraphProjectionService projectionService;

    @Autowired
    private MemoryGraphQueryService queryService;

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
    void rebuildProjectsActiveLongTermMemoryIntoBaselineGraphAndIsIdempotent() {
        var first = longTermMemories.save(memory(
            "mem-v5-3-01-a",
            MemoryStatus.ACTIVE,
            "Payment charge timeout is reusable",
            List.of("payment", "timeout"),
            Map.of(
                "systemName", " Billing ",
                "module", " Payment ",
                "apiPath", "/API//Payments/Charge/",
                "httpMethod", "post",
                "factType", "failure_pattern",
                "factFingerprint", "fact:issue01-a",
                "evidenceSummary", "POST charge timed out under billing payment"
            )
        ));
        longTermMemories.save(memory(
            "mem-v5-3-01-b",
            MemoryStatus.ACTIVE,
            "Payment charge retry should reuse same graph identity",
            List.of("payment", "retry"),
            Map.of(
                "identityHints", Map.of(
                    "systemName", "billing",
                    "module", "payment",
                    "apiPath", "api/payments/charge",
                    "httpMethod", "POST"
                ),
                "factType", "testing_pattern",
                "factFingerprint", "fact:issue01-b",
                "evidenceSummaries", List.of("Same API path with normalized method casing")
            )
        ));
        longTermMemories.save(memory(
            "mem-v5-3-01-inactive",
            MemoryStatus.INACTIVE,
            "Inactive memory must not enter graph",
            List.of("inactive"),
            Map.of("systemName", "legacy", "module", "ignored", "apiPath", "/ignored", "httpMethod", "GET")
        ));
        longTermMemories.save(memory(
            "mem-v5-3-01-archived",
            MemoryStatus.ARCHIVED,
            "Archived memory must not enter graph",
            List.of("archived"),
            Map.of("systemName", "archive", "module", "ignored", "apiPath", "/archived", "httpMethod", "GET")
        ));

        var summary = projectionService.rebuild();
        var nodeCount = nodes.count();
        var edgeCount = edges.count();
        var apiNode = nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.API_PATH, "/api/payments/charge")
            .getFirst();
        var moduleNode = nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.MODULE, "payment")
            .getFirst();
        var systemNode = nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.SYSTEM, "billing")
            .getFirst();

        assertThat(summary.processedMemoryCount()).isEqualTo(2);
        assertThat(summary.skippedMemoryCount()).isZero();
        assertThat(apiNode.getDisplayValue()).isEqualTo("api/payments/charge");
        assertThat(apiNode.getOccurrenceCount()).isEqualTo(2);
        assertThat(apiNode.getSourceMemoryIds()).containsExactlyInAnyOrder(first.getMemoryId(), "mem-v5-3-01-b");
        assertThat(apiNode.getSourceRefs()).containsExactlyInAnyOrder("src:mem-v5-3-01-a", "src:mem-v5-3-01-b");
        assertThat(apiNode.getFactFingerprints()).containsExactlyInAnyOrder("fact:issue01-a", "fact:issue01-b");
        assertThat(nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.SYSTEM, "legacy")).isEmpty();
        assertThat(nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.SYSTEM, "archive")).isEmpty();

        assertThat(edges.findAll()).anySatisfy(edge -> {
            assertThat(edge.getSourceNodeId()).isEqualTo(apiNode.getNodeId());
            assertThat(edge.getTargetNodeId()).isEqualTo(moduleNode.getNodeId());
            assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.API_BELONGS_TO_MODULE);
            assertThat(edge.getOccurrenceCount()).isEqualTo(2);
        }).anySatisfy(edge -> {
            assertThat(edge.getSourceNodeId()).isEqualTo(moduleNode.getNodeId());
            assertThat(edge.getTargetNodeId()).isEqualTo(systemNode.getNodeId());
            assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.MODULE_BELONGS_TO_SYSTEM);
        }).anySatisfy(edge -> {
            assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.FACT_HAS_TAG);
            assertThat(edge.getSourceMemoryIds()).isNotEmpty();
        });

        var query = queryService.queryRelated(new MemoryGraphSeed(MemoryGraphEntityType.API_PATH, "/api/payments/charge", null), 2, 10);
        assertThat(query.relatedEntities())
            .extracting(MemoryGraphRelatedEntity::entityType)
            .contains(MemoryGraphEntityType.MODULE, MemoryGraphEntityType.SYSTEM, MemoryGraphEntityType.HTTP_METHOD);
        assertThat(query.relatedMemories())
            .extracting(MemoryGraphRelatedMemory::memoryId)
            .containsExactlyInAnyOrder("mem-v5-3-01-a", "mem-v5-3-01-b");
        assertThat(query.relatedMemories())
            .allSatisfy(memory -> assertThat(memory.matchReason()).startsWith("graph-"));

        var secondSummary = projectionService.rebuild();
        assertThat(secondSummary.processedMemoryCount()).isEqualTo(2);
        assertThat(nodes.count()).isEqualTo(nodeCount);
        assertThat(edges.count()).isEqualTo(edgeCount);
        assertThat(nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.API_PATH, "/api/payments/charge")
            .getFirst()
            .getOccurrenceCount()).isEqualTo(2);
    }

    private LongTermMemory memory(
        String memoryId,
        MemoryStatus status,
        String summary,
        List<String> tags,
        Map<String, Object> metadata
    ) {
        var memory = new LongTermMemory();
        memory.setMemoryId(memoryId);
        memory.setMemoryType(MemoryType.LONG_TERM);
        memory.setScopeType(MemoryScopeType.FAILURE_PATTERN);
        memory.setSummary(summary);
        memory.setContent(summary + " content");
        memory.setFullContent(summary + " full evidence");
        memory.setTags(tags);
        memory.setSourceType(MemorySourceType.EXECUTION_RESULT);
        memory.setSourceRef("src:" + memoryId);
        memory.setConfidence(0.82f);
        memory.setImportance(0.76f);
        memory.setSuccessContribution(0.44f);
        memory.setHitCount(0);
        memory.setStatus(status);
        memory.setMetadata(new LinkedHashMap<>(metadata));
        memory.setEmbedding(new float[1024]);
        return memory;
    }
}
