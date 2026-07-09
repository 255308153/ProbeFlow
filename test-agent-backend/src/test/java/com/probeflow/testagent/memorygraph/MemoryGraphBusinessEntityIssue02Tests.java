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
class MemoryGraphBusinessEntityIssue02Tests {

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
    void projectsErrorCodeFailureClassificationBusinessEntityAndPreconditionRelations() {
        longTermMemories.save(memory(
            "mem-v5-3-02-failure",
            0.86f,
            "Payment charge maps PAY_401 to auth token failure",
            List.of("payment", "auth"),
            Map.of(
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "httpMethod", "POST",
                "errorCode", "pay-401",
                "businessEntity", " Payment Order ",
                "failureClassification", "auth failure",
                "factType", "failure_pattern",
                "factFingerprint", "fact:issue02-failure",
                "evidenceSummary", "PAY_401 was observed on charge API"
            )
        ));
        longTermMemories.save(memory(
            "mem-v5-3-02-precondition",
            0.84f,
            "Payment order must exist before charge execution",
            List.of("payment", "business-precondition"),
            Map.of(
                "systemName", "billing",
                "module", "payment",
                "businessEntity", "payment order",
                "precondition", "order exists and is payable",
                "factType", "business_precondition_fact",
                "factFingerprint", "fact:issue02-precondition",
                "evidenceSummary", "Prepare a payable order before executing charge"
            )
        ));
        longTermMemories.save(memory(
            "mem-v5-3-02-low",
            0.30f,
            "Weak low confidence PAY_402 relation should stay out of default query",
            List.of("payment"),
            Map.of(
                "systemName", "billing",
                "module", "payment",
                "apiPath", "/api/payments/charge",
                "errorCode", "PAY_402",
                "factType", "failure_pattern"
            )
        ));

        projectionService.rebuild();

        assertThat(nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.ERROR_CODE, "PAY_401")).hasSize(1);
        assertThat(nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.FAILURE_CLASSIFICATION, "AUTH_FAILURE")).hasSize(1);
        assertThat(nodes.findAllByEntityTypeAndNormalizedValue(MemoryGraphEntityType.BUSINESS_ENTITY, "payment order")).hasSize(1);

        assertThat(edges.findAll())
            .anySatisfy(edge -> assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.ERROR_OBSERVED_ON_API))
            .anySatisfy(edge -> assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.FAILURE_CLASSIFIED_AS))
            .anySatisfy(edge -> assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.BUSINESS_ENTITY_RELATED_TO_API))
            .anySatisfy(edge -> assertThat(edge.getRelationType()).isEqualTo(MemoryGraphRelationType.BUSINESS_ENTITY_REQUIRES_PRECONDITION));

        var apiQuery = queryService.queryRelated(new MemoryGraphSeed(MemoryGraphEntityType.API_PATH, "/api/payments/charge", null), 2, 20);
        assertThat(apiQuery.relatedEntities())
            .extracting(MemoryGraphRelatedEntity::entityType)
            .contains(MemoryGraphEntityType.MODULE, MemoryGraphEntityType.SYSTEM, MemoryGraphEntityType.ERROR_CODE, MemoryGraphEntityType.FAILURE_CLASSIFICATION);
        assertThat(apiQuery.relatedEntities())
            .extracting(MemoryGraphRelatedEntity::displayValue)
            .doesNotContain("PAY_402");

        var errorQuery = queryService.queryRelated(new MemoryGraphSeed(MemoryGraphEntityType.ERROR_CODE, "PAY_401", null), 2, 20);
        assertThat(errorQuery.relatedEntities())
            .extracting(MemoryGraphRelatedEntity::entityType)
            .contains(MemoryGraphEntityType.API_PATH, MemoryGraphEntityType.MODULE, MemoryGraphEntityType.FAILURE_CLASSIFICATION);
        assertThat(errorQuery.relatedMemories()).anySatisfy(memory -> {
            assertThat(memory.memoryId()).isEqualTo("mem-v5-3-02-failure");
            assertThat(memory.matchReason()).isEqualTo("graph-related-error-code");
            assertThat(memory.relationPath()).contains(MemoryGraphRelationType.ERROR_OBSERVED_ON_API.name());
            assertThat(memory.evidenceSummaries()).contains("PAY_401 was observed on charge API");
        });

        var businessQuery = queryService.queryRelated(new MemoryGraphSeed(MemoryGraphEntityType.BUSINESS_ENTITY, "payment order", null), 2, 20);
        assertThat(businessQuery.relatedEntities())
            .extracting(MemoryGraphRelatedEntity::entityType)
            .contains(MemoryGraphEntityType.API_PATH, MemoryGraphEntityType.PRECONDITION);
        assertThat(businessQuery.relatedMemories())
            .extracting(MemoryGraphRelatedMemory::memoryId)
            .contains("mem-v5-3-02-failure", "mem-v5-3-02-precondition")
            .doesNotContain("mem-v5-3-02-low");
    }

    private LongTermMemory memory(
        String memoryId,
        float confidence,
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
        memory.setConfidence(confidence);
        memory.setImportance(confidence);
        memory.setSuccessContribution(0.44f);
        memory.setHitCount(0);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setMetadata(new LinkedHashMap<>(metadata));
        memory.setEmbedding(new float[1024]);
        return memory;
    }
}
