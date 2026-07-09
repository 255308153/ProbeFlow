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
class MemoryGraphSuiteVariableIssue03Tests {

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
    void projectsSuiteVariableProducerConsumerAndSourcePathWithSuiteScopedVariableIdentity() {
        longTermMemories.save(memory(
            "mem-v5-3-03-order",
            "Order suite variable extraction failure points to upstream create order step",
            Map.of(
                "suiteId", "checkout-suite",
                "caseId", "case-checkout-happy",
                "rootStepId", "step-create-order",
                "downstreamStepId", "step-pay-order",
                "variableKey", "orderId",
                "sourcePath", "$.data.orderId",
                "factType", "variable_extraction_fact",
                "factFingerprint", "fact:issue03-order",
                "evidenceSummary", "orderId is produced by create-order and consumed by pay-order"
            )
        ));
        longTermMemories.save(memory(
            "mem-v5-3-03-refund",
            "Refund suite uses an orderId variable with a different meaning",
            Map.of(
                "suiteId", "refund-suite",
                "caseId", "case-refund",
                "rootStepId", "step-query-refund",
                "downstreamStepId", "step-submit-refund",
                "variableKey", "orderId",
                "sourcePath", "$.data.refund.orderId",
                "factType", "variable_extraction_fact",
                "factFingerprint", "fact:issue03-refund"
            )
        ));

        projectionService.rebuild();

        assertThat(nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.VARIABLE_KEY, "orderid"))
            .hasSize(2)
            .extracting(MemoryGraphNode::getScope)
            .containsExactlyInAnyOrder("suite:checkout-suite", "suite:refund-suite");
        assertThat(edges.findAll())
            .anySatisfy(edge -> assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.SUITE_CONTAINS_CASE))
            .anySatisfy(edge -> assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.SUITE_STEP_PRODUCES_VARIABLE))
            .anySatisfy(edge -> assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.SUITE_STEP_CONSUMES_VARIABLE))
            .anySatisfy(edge -> assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.VARIABLE_EXTRACTED_FROM_SOURCE_PATH));

        var variableQuery = queryService.queryRelated(
            new MemoryGraphSeed(MemoryGraphEntityType.VARIABLE_KEY, "orderId", "suite:checkout-suite"),
            3,
            20
        );
        assertThat(variableQuery.relatedEntities())
            .extracting(MemoryGraphRelatedEntity::displayValue)
            .contains("step-create-order", "step-pay-order", "$.data.orderId", "checkout-suite", "case-checkout-happy")
            .doesNotContain("refund-suite", "$.data.refund.orderId");
        assertThat(variableQuery.relatedMemories()).anySatisfy(memory -> {
            assertThat(memory.memoryId()).isEqualTo("mem-v5-3-03-order");
            assertThat(memory.matchReason()).isEqualTo("graph-related-variable");
            assertThat(memory.relationPath()).contains(MemoryGraphRelationType.SUITE_STEP_PRODUCES_VARIABLE.name());
            assertThat(memory.sourceMemoryIds()).contains("mem-v5-3-03-order");
        });

        var sourcePathQuery = queryService.queryRelated(
            new MemoryGraphSeed(MemoryGraphEntityType.SOURCE_PATH, "$.data.orderId", "suite:checkout-suite"),
            2,
            20
        );
        assertThat(sourcePathQuery.relatedEntities())
            .extracting(MemoryGraphRelatedEntity::entityType)
            .contains(MemoryGraphEntityType.VARIABLE_KEY);
        assertThat(sourcePathQuery.relatedMemories())
            .extracting(MemoryGraphRelatedMemory::memoryId)
            .contains("mem-v5-3-03-order")
            .doesNotContain("mem-v5-3-03-refund");
    }

    private LongTermMemory memory(String memoryId, String summary, Map<String, Object> metadata) {
        var memory = new LongTermMemory();
        memory.setMemoryId(memoryId);
        memory.setMemoryType(MemoryType.LONG_TERM);
        memory.setScopeType(MemoryScopeType.FAILURE_PATTERN);
        memory.setSummary(summary);
        memory.setContent(summary + " content");
        memory.setFullContent(summary + " full evidence");
        memory.setTags(List.of("suite", "variable-extraction"));
        memory.setSourceType(MemorySourceType.EXECUTION_RESULT);
        memory.setSourceRef("src:" + memoryId);
        memory.setConfidence(0.84f);
        memory.setImportance(0.78f);
        memory.setSuccessContribution(0.44f);
        memory.setHitCount(0);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setMetadata(new LinkedHashMap<>(metadata));
        memory.setEmbedding(new float[1024]);
        return memory;
    }
}
