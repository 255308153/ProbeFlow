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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MemoryGraphGovernanceIssue05Tests {

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

    @Test
    void mergesProvenanceAppliesConfidenceGateAndProjectsReinforcesAndConflictAuditEdges() {
        longTermMemories.save(memory(
            "mem-v5-3-05-a",
            0.78f,
            0.72f,
            List.of("payment", "human-approved"),
            Map.of(
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "httpMethod", "POST",
                "factType", "failure_pattern",
                "factFingerprint", "fact:shared-charge-timeout",
                "mergedSourceRefs", List.of("exec:charge-timeout-a", "exec:shared-timeout"),
                "identityHints", Map.of("systemName", "billing", "module", "payment", "apiPath", "/api/payments/charge"),
                "evidenceLedger", List.of(
                    Map.of("summary", "charge timeout reproduced in nightly suite", "sourceRef", "exec:charge-timeout-a"),
                    Map.of("summary", "human approved this timeout pattern", "sourceRef", "human:review-1")
                ),
                "humanConfirmed", true
            )
        ));
        longTermMemories.save(memory(
            "mem-v5-3-05-b",
            0.74f,
            0.70f,
            List.of("payment"),
            Map.of(
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "httpMethod", "POST",
                "factType", "failure_pattern",
                "factFingerprint", "fact:shared-charge-timeout",
                "sourceRef", "exec:charge-timeout-b",
                "mergedSourceRefs", List.of("exec:shared-timeout"),
                "identityHints", Map.of("systemName", "billing", "module", "payment", "apiPath", "/api/payments/charge"),
                "evidenceSummary", "second active memory reinforces the same charge timeout fact"
            )
        ));
        longTermMemories.save(memory(
            "mem-v5-3-05-conflict",
            0.81f,
            0.76f,
            List.of("payment"),
            Map.of(
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "httpMethod", "POST",
                "factType", "failure_pattern",
                "factFingerprint", "fact:conflicting-timeout-identity",
                "identityConflictAudit", Map.of(
                    "existingMemoryId", "mem-v5-3-05-a",
                    "reason", "identity-conflict",
                    "identityConflicts", List.of(Map.of("field", "errorCode", "existingValue", "TIMEOUT", "candidateValue", "AUTH"))
                ),
                "evidenceSummary", "identity conflict audit should be visible only through audit query"
            )
        ));
        longTermMemories.save(memory(
            "mem-v5-3-05-low",
            0.12f,
            0.10f,
            List.of("payment"),
            Map.of(
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "errorCode", "PAY_LOW",
                "factType", "failure_pattern",
                "factFingerprint", "fact:low-confidence"
            )
        ));

        projectionService.rebuild();
        var nodeCount = nodes.count();
        var edgeCount = edges.count();

        var apiNode = nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.API_PATH, "/api/payments/charge").getFirst();
        assertThat(apiNode.getSourceMemoryIds())
            .contains("mem-v5-3-05-a", "mem-v5-3-05-b", "mem-v5-3-05-conflict", "mem-v5-3-05-low");
        assertThat(apiNode.getSourceRefs())
            .contains("src:mem-v5-3-05-a", "exec:charge-timeout-a", "exec:charge-timeout-b", "exec:shared-timeout")
            .doesNotHaveDuplicates();
        assertThat(apiNode.getFactFingerprints())
            .contains("fact:shared-charge-timeout", "fact:conflicting-timeout-identity", "fact:low-confidence");
        assertThat(apiNode.getEvidenceSummaries())
            .contains("charge timeout reproduced in nightly suite", "human approved this timeout pattern");
        assertThat(apiNode.getOccurrenceCount()).isEqualTo(4);
        assertThat(apiNode.getConfidence()).isLessThanOrEqualTo(0.98d);
        assertThat(apiNode.getFirstSeenAt()).isNotNull();
        assertThat(apiNode.getLastSeenAt()).isNotNull();

        assertThat(edges.findAll())
            .anySatisfy(edge -> {
                assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.FACT_REINFORCES_FACT);
                assertThat(edge.getSourceMemoryIds()).contains("mem-v5-3-05-a", "mem-v5-3-05-b");
                assertThat(edge.getSourceRefs()).contains("exec:shared-timeout");
                assertThat(edge.getFactFingerprints()).contains("fact:shared-charge-timeout");
                assertThat(edge.getEvidenceSummaries()).contains("shared fact identity reinforces both long-term memories");
                assertThat(edge.getOccurrenceCount()).isEqualTo(2);
                assertThat(edge.getCreatedAt()).isNotNull();
                assertThat(edge.getUpdatedAt()).isNotNull();
            })
            .anySatisfy(edge -> {
                assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.FACT_CONFLICTS_WITH_FACT);
                assertThat(edge.getSourceMemoryIds()).contains("mem-v5-3-05-conflict");
                assertThat(edge.getEvidenceSummaries()).contains("identity conflict audit points to mem-v5-3-05-a");
            });

        var related = queryService.queryRelated(new MemoryGraphSeed(MemoryGraphEntityType.API_PATH, "/api/payments/charge", null), 3, 20);
        assertThat(related.relatedMemories()).anySatisfy(memory -> {
            assertThat(memory.memoryId()).isEqualTo("mem-v5-3-05-a");
            assertThat(memory.channel()).isEqualTo("graph");
            assertThat(memory.sourceRefs()).contains("exec:shared-timeout");
            assertThat(memory.factFingerprints()).contains("fact:shared-charge-timeout");
            assertThat(memory.confidence()).isGreaterThanOrEqualTo(MemoryGraphQueryService.DEFAULT_CONFIDENCE_GATE);
        });
        assertThat(related.relatedMemories())
            .extracting(MemoryGraphRelatedMemory::memoryId)
            .doesNotContain("mem-v5-3-05-low");
        assertThat(related.relatedMemories())
            .flatExtracting(MemoryGraphRelatedMemory::relationPath)
            .doesNotContain(MemoryGraphRelationType.FACT_CONFLICTS_WITH_FACT.name());

        var conflictAudit = queryService.queryAuditRelations(
            new MemoryGraphSeed(MemoryGraphEntityType.MEMORY_FACT, "mem-v5-3-05-conflict", null),
            10
        );
        assertThat(conflictAudit).singleElement().satisfies(edge -> {
            assertThat(edge.relationType()).isEqualTo(MemoryGraphRelationType.FACT_CONFLICTS_WITH_FACT);
            assertThat(edge.sourceMemoryIds()).contains("mem-v5-3-05-conflict");
            assertThat(edge.evidenceSummaries()).contains("identity conflict audit points to mem-v5-3-05-a");
        });

        projectionService.rebuild();
        assertThat(nodes.count()).isEqualTo(nodeCount);
        assertThat(edges.count()).isEqualTo(edgeCount);
        assertThat(nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.API_PATH, "/api/payments/charge")
            .getFirst()
            .getSourceRefs()).doesNotHaveDuplicates();
    }

    private LongTermMemory memory(
        String memoryId,
        float confidence,
        float importance,
        List<String> tags,
        Map<String, Object> metadata
    ) {
        var memory = new LongTermMemory();
        memory.setMemoryId(memoryId);
        memory.setMemoryType(MemoryType.LONG_TERM);
        memory.setScopeType(MemoryScopeType.FAILURE_PATTERN);
        memory.setSummary(memoryId + " summary");
        memory.setContent(memoryId + " content");
        memory.setFullContent(memoryId + " full evidence");
        memory.setTags(tags);
        memory.setSourceType(MemorySourceType.MEMORY_REFINERY);
        memory.setSourceRef("src:" + memoryId);
        memory.setConfidence(confidence);
        memory.setImportance(importance);
        memory.setSuccessContribution(0.40f);
        memory.setHitCount(0);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setMetadata(new LinkedHashMap<>(metadata));
        memory.setEmbedding(new float[1024]);
        return memory;
    }
}
